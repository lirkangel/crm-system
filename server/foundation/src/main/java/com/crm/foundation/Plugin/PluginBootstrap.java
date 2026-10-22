package com.crm.foundation.Plugin;

import jakarta.persistence.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Early plugin phase (design 6.1): runs while Foundation's
 * {@code EntityManagerFactory} is being built, after Foundation's own Flyway
 * migrations. For each valid plugin package it builds the plugin classloader,
 * migrates the plugin schema, finds its {@code @Entity} classes and validates
 * them in isolation ({@link PluginEntityValidator}: own schema only, trial
 * mapping), so {@link PluginJpaContributor} can add them to Foundation's
 * persistence unit — Hibernate's {@code ddl-auto: validate} then sees the
 * plugin tables, and a plugin that would break it is FAILED instead.
 *
 * <p>Runs once (memoized). Never throws: a plugin that fails is reported
 * {@code FAILED} and left out; {@code DISABLED}/{@code UNINSTALL_PENDING}
 * plugins are {@code SKIPPED} without being touched.
 */
@Component
public class PluginBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PluginBootstrap.class);
    private static final String REGISTRY_STATE_SQL = "SELECT state FROM plugin_registry WHERE plugin_id = ?";

    private final ObjectProvider<FlywayMigrationInitializer> foundationMigrations;
    private final ObjectProvider<PersistenceManagedTypes> foundationManagedTypes;
    private final PluginDiscovery discovery;
    private final PluginLoader loader;
    private final PluginMigrator migrator;
    private final PluginEntityScanner entityScanner;
    private final PluginEntityValidator entityValidator;
    private final JdbcTemplate jdbc;

    private PluginBootstrapReport report;

    public PluginBootstrap(
            ObjectProvider<FlywayMigrationInitializer> foundationMigrations,
            ObjectProvider<PersistenceManagedTypes> foundationManagedTypes,
            PluginDiscovery discovery,
            PluginLoader loader,
            PluginMigrator migrator,
            PluginEntityScanner entityScanner,
            PluginEntityValidator entityValidator,
            JdbcTemplate jdbc) {
        this.foundationMigrations = foundationMigrations;
        this.foundationManagedTypes = foundationManagedTypes;
        this.discovery = discovery;
        this.loader = loader;
        this.migrator = migrator;
        this.entityScanner = entityScanner;
        this.entityValidator = entityValidator;
        this.jdbc = jdbc;
    }

    public synchronized PluginBootstrapReport report() {
        if (report == null) {
            report = bootstrap();
        }
        return report;
    }

    private PluginBootstrapReport bootstrap() {
        // Foundation's schema (plugin_registry included) must exist before any plugin work
        foundationMigrations.getIfAvailable();
        List<BootstrappedPlugin> plugins = new ArrayList<>();
        try {
            // JPA entity names are global to the shared persistence unit: Foundation's
            // come first, then each READY plugin's, in discovery order (first claim wins)
            Map<String, String> claimedEntityNames = new HashMap<>(foundationEntityNames());
            for (PluginDiscoveryResult result : discovery.scan()) {
                if (result.isValid()) {
                    plugins.add(bootstrapIsolated(result, claimedEntityNames));
                }
            }
        } catch (RuntimeException e) {
            log.error("Plugin discovery failed; starting without plugins: {}", e.getMessage(), e);
        }
        return new PluginBootstrapReport(plugins);
    }

    private BootstrappedPlugin bootstrapIsolated(
            PluginDiscoveryResult result, Map<String, String> claimedEntityNames) {
        PluginManifest manifest = result.manifest();
        ParentLastClassLoader classLoader = null;
        try {
            String state = registryState(manifest.id());
            if (PluginState.DISABLED.name().equals(state) || PluginState.UNINSTALL_PENDING.name().equals(state)) {
                log.info("Plugin {} is {}; contributing no entities", manifest.id(), state);
                return BootstrappedPlugin.skipped(result.packagePath(), manifest);
            }
            Path zip = Path.of(result.packagePath());
            classLoader = loader.createClassLoader(zip, manifest);
            migrator.migrate(zip, manifest);
            List<String> entities = entityScanner.scan(classLoader, basePackage(manifest));
            Map<String, String> entityNames =
                entityValidator.validate(manifest, classLoader, entities, claimedEntityNames);
            entityNames.forEach((name, className) ->
                claimedEntityNames.put(name, "plugin " + manifest.id() + " entity " + className));
            log.info("Plugin {} bootstrapped with {} entity class(es)", manifest.id(), entities.size());
            return BootstrappedPlugin.ready(result.packagePath(), manifest, classLoader, entities);
        } catch (Exception | LinkageError e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("Plugin {} failed to bootstrap: {}", manifest.id(), reason, e);
            closeQuietly(classLoader);
            return BootstrappedPlugin.failed(result.packagePath(), manifest, reason);
        }
    }

    private Map<String, String> foundationEntityNames() {
        PersistenceManagedTypes managedTypes = foundationManagedTypes.getIfAvailable();
        return managedTypes == null
            ? Map.of()
            : foundationEntityNames(managedTypes.getManagedClassNames(), PluginBootstrap.class.getClassLoader());
    }

    /**
     * JPA entity name → {@code "Foundation entity <class>"} for the
     * {@code @Entity} classes among {@code classNames} (embeddables,
     * mapped superclasses and converters have no entity name).
     */
    static Map<String, String> foundationEntityNames(List<String> classNames, ClassLoader classLoader) {
        Map<String, String> names = new HashMap<>();
        for (String className : classNames) {
            try {
                Entity entity = Class.forName(className, false, classLoader).getAnnotation(Entity.class);
                if (entity != null) {
                    String name = entity.name().isEmpty()
                        ? className.substring(className.lastIndexOf('.') + 1)
                        : entity.name();
                    names.put(name, "Foundation entity " + className);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                log.warn("Cannot read the entity name of Foundation class {}: {}", className, e.toString());
            }
        }
        return names;
    }

    private String registryState(String pluginId) {
        List<String> states = jdbc.queryForList(REGISTRY_STATE_SQL, String.class, pluginId);
        return states.isEmpty() ? null : states.get(0);
    }

    private static String basePackage(PluginManifest manifest) {
        int lastDot = manifest.entry().lastIndexOf('.');
        return lastDot < 0 ? "" : manifest.entry().substring(0, lastDot);
    }

    private static void closeQuietly(ParentLastClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException e) {
            log.warn("Could not close classloader {}: {}", classLoader.getName(), e.getMessage());
        }
    }
}
