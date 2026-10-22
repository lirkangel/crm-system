package com.crm.foundation.Plugin;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.hibernate.mapping.Table;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks one plugin's {@code @Entity} classes in isolation before they join
 * Foundation's persistence unit (design 6.1), so a bad plugin is FAILED
 * instead of breaking Foundation's {@code ddl-auto: validate} for everyone.
 *
 * <p>It builds a throwaway {@code EntityManagerFactory} from only these
 * classes, on Foundation's {@link DataSource} and Hibernate settings, with
 * {@code hbm2ddl.auto=validate} and the plugin classloader in
 * {@code hibernate.classLoaders}. It is always closed. Two checks run on it:
 * <ol>
 *   <li><b>Schema isolation</b> — every table and sequence in the mapping
 *       Hibernate actually built must live in the manifest's {@code schema}.
 *       That includes tables Hibernate creates implicitly (an
 *       {@code @ElementCollection} without {@code @CollectionTable}, a
 *       {@code @ManyToMany} without {@code @JoinTable}, collections inside
 *       embeddables, overrides); a table without a schema is in the default
 *       schema and counts as a mismatch. The check runs as a Hibernate
 *       {@link Integrator} — before schema validation — and again once the
 *       factory is up, when id generators have registered their sequences
 *       and tables.</li>
 *   <li><b>Trial mapping</b> — Hibernate's own mapping and
 *       {@code hbm2ddl} validation of these classes against the database.
 *       Because Foundation's entities are absent, an association to one
 *       fails here too.</li>
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
     * @throws InvalidPluginPackageException when the entities map a table or
     *         sequence outside {@code manifest.schema()}, or fail Hibernate's
     *         mapping/schema validation
     */
    public void validate(PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames) {
        if (entityClassNames.isEmpty()) {
            return;
        }
        SchemaGuard guard = new SchemaGuard(manifest);
        LocalContainerEntityManagerFactoryBean trial =
            trialFactory(manifest, classLoader, entityClassNames, guard);
        try {
            trial.afterPropertiesSet();
            guard.recheck();
        } catch (InvalidPluginPackageException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            if (cause instanceof InvalidPluginPackageException schemaViolation) {
                throw schemaViolation;
            }
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
            throw new InvalidPluginPackageException("entity mapping validation failed: " + detail, e);
        } finally {
            closeQuietly(manifest, trial);
        }
    }

    /**
     * Tables and sequences of {@code metadata} that are not in {@code schema},
     * as {@code "table public.users"}; empty when all are inside it.
     */
    static List<String> schemaViolations(Metadata metadata, String schema) {
        List<String> violations = new ArrayList<>();
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            Identifier namespaceSchema = namespace.getPhysicalName().getSchema();
            String actual = namespaceSchema == null ? null : namespaceSchema.getText();
            if (Objects.equals(schema, actual)) {
                continue;
            }
            String prefix = (actual == null ? "<default schema>" : actual) + ".";
            for (Table table : namespace.getTables()) {
                violations.add("table " + prefix + (table.isSubselect() ? "<subselect>" : table.getName()));
            }
            for (Sequence sequence : namespace.getSequences()) {
                violations.add("sequence " + prefix + sequence.getName().getSequenceName().getText());
            }
        }
        return violations;
    }

    private static void closeQuietly(PluginManifest manifest, LocalContainerEntityManagerFactoryBean trial) {
        try {
            trial.destroy();
        } catch (RuntimeException e) {
            log.warn("Could not close the trial EntityManagerFactory of plugin {}: {}", manifest.id(), e.toString());
        }
    }

    /** The unstarted throwaway factory; {@link #validate} starts and always destroys it. */
    LocalContainerEntityManagerFactoryBean trialFactory(
            PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames, Integrator schemaGuard) {
        Map<String, Object> properties = new HashMap<>(hibernateProperties.determineHibernateProperties(
            jpaProperties.getProperties(), new HibernateSettings()));
        // validate only, whatever Foundation's own settings say
        properties.remove(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION);
        properties.put(AvailableSettings.HBM2DDL_AUTO, "validate");
        properties.put(AvailableSettings.CLASSLOADERS, List.of(classLoader));
        properties.put(JpaSettings.INTEGRATOR_PROVIDER, (IntegratorProvider) () -> List.of(schemaGuard));

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

    /**
     * Rejects the plugin from inside the trial {@code SessionFactory} build,
     * where Hibernate hands over its boot {@link Metadata} — integrators run
     * before {@code hbm2ddl} validation, so the reason is the schema escape,
     * not whatever validation would make of the foreign table.
     */
    private static final class SchemaGuard implements Integrator {

        private final PluginManifest manifest;
        private Metadata metadata;

        SchemaGuard(PluginManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                SessionFactoryImplementor sessionFactory) {
            this.metadata = metadata;
            check();
        }

        /** Id generators register their sequences/tables after integration; check those too. */
        void recheck() {
            if (metadata == null) {
                throw new InvalidPluginPackageException(
                    "plugin " + manifest.id() + ": entity mapping was not inspected for schema isolation");
            }
            check();
        }

        private void check() {
            List<String> violations = schemaViolations(metadata, manifest.schema());
            if (!violations.isEmpty()) {
                throw new InvalidPluginPackageException("plugin " + manifest.id()
                    + " maps database objects outside its schema '" + manifest.schema() + "': "
                    + String.join(", ", violations));
            }
        }

        @Override
        public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry registry) {
            // nothing to undo
        }
    }
}
