package com.crm.foundation.Plugin;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinTable;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;
import jakarta.persistence.Table;
import org.hibernate.cfg.AvailableSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateSettings;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks one plugin's {@code @Entity} classes in isolation before they join
 * Foundation's persistence unit (design 6.1), so a bad plugin is FAILED
 * instead of breaking Foundation's {@code ddl-auto: validate} for everyone:
 * <ol>
 *   <li><b>Schema isolation</b> — every table the entities name
 *       ({@code @Table}, {@code @SecondaryTable}, {@code @JoinTable},
 *       {@code @CollectionTable}) must declare the manifest's {@code schema};
 *       a missing schema counts as a mismatch.</li>
 *   <li><b>Trial mapping</b> — a throwaway {@code EntityManagerFactory} built
 *       from only these classes, on Foundation's {@link DataSource} and
 *       Hibernate settings, with {@code hbm2ddl.auto=validate} and the plugin
 *       classloader in {@code hibernate.classLoaders}. It is always closed.
 *       Because Foundation's entities are absent, an association to one fails
 *       here too.</li>
 * </ol>
 */
@Component
public class PluginEntityValidator {

    private static final Logger log = LoggerFactory.getLogger(PluginEntityValidator.class);

    private final DataSource dataSource;
    private final JpaProperties jpaProperties;
    private final HibernateProperties hibernateProperties;

    public PluginEntityValidator(
            DataSource dataSource, JpaProperties jpaProperties, HibernateProperties hibernateProperties) {
        this.dataSource = dataSource;
        this.jpaProperties = jpaProperties;
        this.hibernateProperties = hibernateProperties;
    }

    /**
     * @throws InvalidPluginPackageException when an entity maps a table outside
     *         {@code manifest.schema()} or the entities fail Hibernate's
     *         mapping/schema validation
     */
    public void validate(PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames) {
        if (entityClassNames.isEmpty()) {
            return;
        }
        for (String className : entityClassNames) {
            checkSchema(manifest, loadEntity(classLoader, className));
        }
        trialMapping(manifest, classLoader, entityClassNames);
    }

    private static Class<?> loadEntity(ClassLoader classLoader, String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new InvalidPluginPackageException(
                "entity " + className + " cannot be loaded: " + e, e);
        }
    }

    private static void checkSchema(PluginManifest manifest, Class<?> entity) {
        List<String> violations = new ArrayList<>();
        Table table = entity.getAnnotation(Table.class);
        if (table == null) {
            violations.add("@Table missing");
        } else {
            checkSchema(manifest, "@Table " + table.name(), table.schema(), violations);
        }
        List<SecondaryTable> secondaryTables = new ArrayList<>();
        SecondaryTable secondary = entity.getAnnotation(SecondaryTable.class);
        if (secondary != null) {
            secondaryTables.add(secondary);
        }
        SecondaryTables secondaries = entity.getAnnotation(SecondaryTables.class);
        if (secondaries != null) {
            secondaryTables.addAll(List.of(secondaries.value()));
        }
        for (SecondaryTable st : secondaryTables) {
            checkSchema(manifest, "@SecondaryTable " + st.name(), st.schema(), violations);
        }
        for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
            List<AccessibleObject> members = new ArrayList<>(List.of(type.getDeclaredFields()));
            members.addAll(List.of(type.getDeclaredMethods()));
            for (AnnotatedElement member : members) {
                JoinTable joinTable = member.getAnnotation(JoinTable.class);
                if (joinTable != null) {
                    checkSchema(manifest, "@JoinTable " + joinTable.name(), joinTable.schema(), violations);
                }
                CollectionTable collectionTable = member.getAnnotation(CollectionTable.class);
                if (collectionTable != null) {
                    checkSchema(manifest, "@CollectionTable " + collectionTable.name(),
                        collectionTable.schema(), violations);
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidPluginPackageException("entity " + entity.getName()
                + " maps tables outside the plugin schema '" + manifest.schema() + "': "
                + String.join(", ", violations));
        }
    }

    private static void checkSchema(PluginManifest manifest, String what, String schema, List<String> violations) {
        if (!manifest.schema().equals(schema)) {
            violations.add(what + " has schema '" + schema + "'");
        }
    }

    private void trialMapping(PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames) {
        LocalContainerEntityManagerFactoryBean trial =
            trialFactory(manifest, classLoader, entityClassNames);
        try {
            trial.afterPropertiesSet();
        } catch (RuntimeException | LinkageError e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
            throw new InvalidPluginPackageException("entity mapping validation failed: " + detail, e);
        } finally {
            closeQuietly(manifest, trial);
        }
    }

    private static void closeQuietly(PluginManifest manifest, LocalContainerEntityManagerFactoryBean trial) {
        try {
            trial.destroy();
        } catch (RuntimeException e) {
            log.warn("Could not close the trial EntityManagerFactory of plugin {}: {}", manifest.id(), e.toString());
        }
    }

    /** The unstarted throwaway factory; {@link #trialMapping} starts and always destroys it. */
    LocalContainerEntityManagerFactoryBean trialFactory(
            PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames) {
        Map<String, Object> properties = new HashMap<>(hibernateProperties.determineHibernateProperties(
            jpaProperties.getProperties(), new HibernateSettings()));
        // validate only, whatever Foundation's own settings say
        properties.remove(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION);
        properties.put(AvailableSettings.HBM2DDL_AUTO, "validate");
        properties.put(AvailableSettings.CLASSLOADERS, List.of(classLoader));

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabasePlatform(jpaProperties.getDatabasePlatform());

        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPersistenceUnitName("plugin-validation:" + manifest.id());
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(vendorAdapter);
        factory.setManagedTypes(PersistenceManagedTypes.of(entityClassNames, List.of()));
        factory.setJpaPropertyMap(properties);
        return factory;
    }
}
