package com.crm.foundation.Plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginBootstrapTest {

    @Mock
    private ObjectProvider<FlywayMigrationInitializer> foundationMigrations;

    @Mock
    private ObjectProvider<PersistenceManagedTypes> foundationManagedTypes;

    /** Subclass doubles — Mockito's inline mock-maker can't instrument concrete classes on this JDK. */
    private final Map<String, String> registryStates = new HashMap<>();
    private final List<String> classLoadersBuilt = new ArrayList<>();
    private final List<String> migratedPlugins = new ArrayList<>();
    private final List<String> failMigrationFor = new ArrayList<>();
    private final Map<String, Throwable> failValidationFor = new HashMap<>();
    private final List<String> classLoadersClosed = new ArrayList<>();
    private final List<String> validatedPlugins = new ArrayList<>();
    /** Plugin id → copy of the claimed entity names the validator was given. */
    private final Map<String, Map<String, String>> claimedSeen = new HashMap<>();
    private int scans;

    private static PluginManifest manifest(String id) {
        return new PluginManifest(
            id, "1.0.0", Map.of(), Map.of(), List.of(), List.of(), "demo", id + ".Entry", "demo");
    }

    private PluginBootstrap bootstrapScanning(PluginDiscoveryResult... results) {
        PluginDiscovery discovery = new PluginDiscovery("unused", new PluginZipValidator(), new PluginManifestParser()) {
            @Override
            public List<PluginDiscoveryResult> scan() {
                scans++;
                return List.of(results);
            }
        };
        PluginLoader loader = new PluginLoader() {
            @Override
            public ParentLastClassLoader createClassLoader(Path zip, PluginManifest manifest) {
                classLoadersBuilt.add(manifest.id());
                return new ParentLastClassLoader(manifest.id(), new URL[0], getClass().getClassLoader()) {
                    @Override
                    public void close() throws IOException {
                        classLoadersClosed.add(manifest.id());
                        super.close();
                    }
                };
            }
        };
        PluginMigrator migrator = new PluginMigrator(null) {
            @Override
            public void migrate(Path zip, PluginManifest manifest) {
                if (failMigrationFor.contains(manifest.id())) {
                    throw new IllegalStateException("migration failed for " + manifest.id());
                }
                migratedPlugins.add(manifest.id());
            }
        };
        PluginEntityScanner scanner = new PluginEntityScanner() {
            @Override
            public List<String> scan(ClassLoader classLoader, String basePackage) {
                return List.of(basePackage + ".Thing");
            }
        };
        PluginEntityValidator validator = new PluginEntityValidator(null, null, null) {
            @Override
            public Map<String, String> validate(PluginManifest manifest, ClassLoader classLoader,
                    List<String> entityClassNames, Map<String, String> claimedEntityNames) {
                claimedSeen.put(manifest.id(), Map.copyOf(claimedEntityNames));
                Throwable failure = failValidationFor.get(manifest.id());
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                validatedPlugins.add(manifest.id() + ":" + entityClassNames);
                // the double names each entity by its class name, so plugins never clash here
                Map<String, String> names = new HashMap<>();
                entityClassNames.forEach(className -> names.put(className, className));
                return names;
            }
        };
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                String state = registryStates.get((String) args[0]);
                return (List<T>) (state == null ? List.of() : List.of(state));
            }
        };
        return new PluginBootstrap(
            foundationMigrations, foundationManagedTypes, discovery, loader, migrator, scanner, validator, jdbc);
    }

    @Test
    void ready_plugin_carries_its_classloader_and_entities() {
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/demo.zip", manifest("com.crm.demo")));

        BootstrappedPlugin plugin = bootstrap.report().plugins().get(0);

        assertThat(plugin.status()).isEqualTo(BootstrappedPlugin.Status.READY);
        assertThat(plugin.classLoader()).isInstanceOf(ParentLastClassLoader.class);
        assertThat(plugin.entityClassNames()).containsExactly("com.crm.demo.Thing");
        assertThat(migratedPlugins).containsExactly("com.crm.demo");
        assertThat(validatedPlugins).containsExactly("com.crm.demo:[com.crm.demo.Thing]");
        assertThat(bootstrap.report().ready()).containsExactly(plugin);
    }

    @Test
    void entity_validation_failure_marks_failed_and_closes_classloader() {
        failValidationFor.put("com.crm.badmap", new InvalidPluginPackageException("missing column [missing_col]"));
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/badmap.zip", manifest("com.crm.badmap")),
            PluginDiscoveryResult.valid("/plugins/good.zip", manifest("com.crm.good")));

        PluginBootstrapReport report = bootstrap.report();

        BootstrappedPlugin badmap = report.plugins().get(0);
        assertThat(badmap.status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(badmap.failureReason()).contains("missing_col");
        assertThat(badmap.classLoader()).isNull();
        assertThat(badmap.entityClassNames()).isEmpty();
        assertThat(classLoadersClosed).containsExactly("com.crm.badmap");
        assertThat(report.ready()).extracting(p -> p.manifest().id()).containsExactly("com.crm.good");
    }

    @Test
    void foundation_and_earlier_ready_plugins_entity_names_are_claimed() {
        when(foundationManagedTypes.getIfAvailable()).thenReturn(
            PersistenceManagedTypes.of(List.of("com.crm.foundation.Domain.User"), List.of()));
        failValidationFor.put("com.crm.b", new InvalidPluginPackageException("bad mapping"));
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/a.zip", manifest("com.crm.a")),
            PluginDiscoveryResult.valid("/plugins/b.zip", manifest("com.crm.b")),
            PluginDiscoveryResult.valid("/plugins/c.zip", manifest("com.crm.c")));

        bootstrap.report();

        assertThat(claimedSeen.get("com.crm.a"))
            .containsExactly(entry("User", "Foundation entity com.crm.foundation.Domain.User"));
        // a is READY, so its names are claimed; b FAILED, so its names are not
        assertThat(claimedSeen.get("com.crm.c")).containsOnly(
            entry("User", "Foundation entity com.crm.foundation.Domain.User"),
            entry("com.crm.a.Thing", "plugin com.crm.a entity com.crm.a.Thing"));
    }

    @Test
    void foundation_entity_names_come_from_entity_classes_only() {
        Map<String, String> names = PluginBootstrap.foundationEntityNames(
            List.of("com.crm.foundation.Domain.User", "com.crm.foundation.Domain.Role",
                "java.lang.String", "com.crm.does.not.Exist"),
            getClass().getClassLoader());

        assertThat(names).containsOnly(
            entry("User", "Foundation entity com.crm.foundation.Domain.User"),
            entry("Role", "Foundation entity com.crm.foundation.Domain.Role"));
    }

    @Test
    void linkage_error_without_message_marks_failed_with_a_reason() {
        failValidationFor.put("com.crm.linkage", new NoClassDefFoundError());
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/linkage.zip", manifest("com.crm.linkage")));

        BootstrappedPlugin plugin = bootstrap.report().plugins().get(0);

        assertThat(plugin.status()).isEqualTo(BootstrappedPlugin.Status.FAILED);
        assertThat(plugin.failureReason()).isEqualTo(NoClassDefFoundError.class.getName());
        assertThat(classLoadersClosed).containsExactly("com.crm.linkage");
    }

    @Test
    void disabled_plugin_is_skipped_and_not_migrated() {
        registryStates.put("com.crm.demo", PluginState.DISABLED.name());
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/demo.zip", manifest("com.crm.demo")));

        BootstrappedPlugin plugin = bootstrap.report().plugins().get(0);

        assertThat(plugin.status()).isEqualTo(BootstrappedPlugin.Status.SKIPPED);
        assertThat(plugin.entityClassNames()).isEmpty();
        assertThat(migratedPlugins).isEmpty();
        assertThat(classLoadersBuilt).isEmpty();
        assertThat(bootstrap.report().ready()).isEmpty();
    }

    @Test
    void migration_failure_marks_failed_without_throwing() {
        failMigrationFor.add("com.crm.broken");
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/broken.zip", manifest("com.crm.broken")),
            PluginDiscoveryResult.valid("/plugins/good.zip", manifest("com.crm.good")));

        PluginBootstrapReport report = bootstrap.report();

        assertThat(report.plugins()).extracting(p -> p.manifest().id(), BootstrappedPlugin::status)
            .containsExactly(
                tuple("com.crm.broken", BootstrappedPlugin.Status.FAILED),
                tuple("com.crm.good", BootstrappedPlugin.Status.READY));
        assertThat(report.plugins().get(0).failureReason()).contains("migration failed for com.crm.broken");
        assertThat(report.ready()).extracting(p -> p.manifest().id()).containsExactly("com.crm.good");
    }

    @Test
    void report_is_computed_once() {
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/demo.zip", manifest("com.crm.demo")));

        PluginBootstrapReport first = bootstrap.report();
        PluginBootstrapReport second = bootstrap.report();

        assertThat(second).isSameAs(first);
        assertThat(scans).isEqualTo(1);
        assertThat(migratedPlugins).containsExactly("com.crm.demo");
        verify(foundationMigrations).getIfAvailable();
    }

    @Test
    void later_package_with_an_already_seen_id_is_left_out_untouched() {
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/hotel-0.1.0.zip", manifest("com.crm.hotel")),
            PluginDiscoveryResult.valid("/plugins/hotel-0.2.0.zip", manifest("com.crm.hotel")),
            PluginDiscoveryResult.valid("/plugins/good.zip", manifest("com.crm.good")));

        PluginBootstrapReport report = bootstrap.report();

        assertThat(report.plugins()).extracting(BootstrappedPlugin::packagePath, BootstrappedPlugin::status)
            .containsExactly(
                tuple("/plugins/hotel-0.1.0.zip", BootstrappedPlugin.Status.READY),
                tuple("/plugins/good.zip", BootstrappedPlugin.Status.READY));
        assertThat(classLoadersBuilt).containsExactly("com.crm.hotel", "com.crm.good");
        assertThat(migratedPlugins).containsExactly("com.crm.hotel", "com.crm.good");
    }

    @Test
    void duplicate_id_stays_out_even_when_the_first_package_failed() {
        failMigrationFor.add("com.crm.hotel");
        PluginBootstrap bootstrap = bootstrapScanning(
            PluginDiscoveryResult.valid("/plugins/hotel-a.zip", manifest("com.crm.hotel")),
            PluginDiscoveryResult.valid("/plugins/hotel-b.zip", manifest("com.crm.hotel")));

        PluginBootstrapReport report = bootstrap.report();

        assertThat(report.plugins()).extracting(BootstrappedPlugin::packagePath, BootstrappedPlugin::status)
            .containsExactly(tuple("/plugins/hotel-a.zip", BootstrappedPlugin.Status.FAILED));
        assertThat(classLoadersBuilt).containsExactly("com.crm.hotel");
    }
}
