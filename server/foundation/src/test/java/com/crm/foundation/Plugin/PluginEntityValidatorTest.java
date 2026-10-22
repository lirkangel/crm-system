package com.crm.foundation.Plugin;

import com.crm.pluginfixtures.SchemaFixtures;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.integrator.spi.Integrator;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class PluginEntityValidatorTest {

    private static final PluginManifest MANIFEST = new PluginManifest(
        "com.crm.x", "1.0.0", Map.of(), Map.of(), List.of(), List.of(), "plugin_x", "x.Entry", "x");

    private final List<String> events = new ArrayList<>();
    private RuntimeException trialFailure;
    /** Boot metadata the double hands to the schema guard, as Hibernate would mid-build. */
    private Metadata builtMetadata;

    /** Validator whose trial factory is a double recording start and close. */
    private final PluginEntityValidator validator = new PluginEntityValidator(null, null, null) {
        @Override
        LocalContainerEntityManagerFactoryBean trialFactory(
                PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames, Integrator guard) {
            return new LocalContainerEntityManagerFactoryBean() {
                @Override
                public void afterPropertiesSet() {
                    events.add("start " + entityClassNames);
                    if (builtMetadata != null) {
                        guard.integrate(builtMetadata, (org.hibernate.boot.spi.BootstrapContext) null, null);
                    }
                    if (trialFailure != null) {
                        throw trialFailure;
                    }
                }

                @Override
                public void destroy() {
                    events.add("close");
                }
            };
        }
    };

    /** Hibernate's boot metadata for {@code entities}, built offline (no database), default schema public. */
    private static Metadata metadata(Class<?>... entities) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
            .applySetting(AvailableSettings.DIALECT, PostgreSQLDialect.class.getName())
            .applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, false)
            .applySetting(AvailableSettings.DEFAULT_SCHEMA, "public")
            .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : entities) {
                sources.addAnnotatedClass(entity);
            }
            return sources.buildMetadata();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static List<String> violations(Class<?>... entities) {
        return PluginEntityValidator.schemaViolations(metadata(entities), "plugin_x");
    }

    @Test
    void entities_and_collection_tables_in_own_schema_pass() {
        assertThat(violations(SchemaFixtures.Own.class, SchemaFixtures.NamedUser.class)).isEmpty();
    }

    @Test
    void entity_table_in_another_schema_is_rejected() {
        assertThat(violations(SchemaFixtures.Own.class, SchemaFixtures.Escape.class))
            .containsExactly("table public.users");
    }

    @Test
    void table_without_schema_counts_as_mismatch() {
        assertThat(violations(SchemaFixtures.NoSchema.class))
            .containsExactly("table <default schema>.no_schema");
    }

    @Test
    void collection_without_collection_table_is_rejected() {
        assertThat(violations(SchemaFixtures.ImplicitCollection.class))
            .singleElement().asString()
            .startsWith("table <default schema>.")
            .endsWith("_roles");
    }

    @Test
    void many_to_many_without_join_table_is_rejected() {
        // JPA default join table name: <owner table>_<target table>
        assertThat(violations(SchemaFixtures.Own.class, SchemaFixtures.ImplicitJoin.class))
            .containsExactly("table <default schema>.implicit_join_own");
    }

    @Test
    void entity_name_already_claimed_is_a_clash() {
        Metadata metadata = metadata(SchemaFixtures.NamedUser.class);

        assertThat(PluginEntityValidator.entityNameClashes(
                metadata, Map.of("User", "Foundation entity com.crm.foundation.Domain.User")))
            .containsExactly("entity name 'User' (" + SchemaFixtures.NamedUser.class.getName()
                + ") is already taken by Foundation entity com.crm.foundation.Domain.User");
        assertThat(PluginEntityValidator.entityNameClashes(
                metadata, Map.of("Note", "plugin com.crm.y entity y.Note")))
            .isEmpty();
    }

    @Test
    void trial_factory_is_started_and_closed_and_entity_names_returned() {
        builtMetadata = metadata(SchemaFixtures.NamedUser.class);

        Map<String, String> names = validator.validate(
            MANIFEST, getClass().getClassLoader(), List.of("x.Thing"), Map.of("Note", "plugin com.crm.y entity y.Note"));

        assertThat(names).containsExactly(entry("User", SchemaFixtures.NamedUser.class.getName()));
        assertThat(events).containsExactly("start [x.Thing]", "close");
    }

    @Test
    void entity_name_clash_rejects_during_the_build_and_factory_still_closed() {
        builtMetadata = metadata(SchemaFixtures.NamedUser.class);

        assertThatThrownBy(() -> validator.validate(MANIFEST, getClass().getClassLoader(), List.of("x.Thing"),
                Map.of("User", "Foundation entity com.crm.foundation.Domain.User")))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessage("plugin com.crm.x entity name 'User' (" + SchemaFixtures.NamedUser.class.getName()
                + ") is already taken by Foundation entity com.crm.foundation.Domain.User");
        assertThat(events).containsExactly("start [x.Thing]", "close");
    }

    @Test
    void schema_guard_rejects_during_the_build_and_factory_still_closed() {
        builtMetadata = metadata(SchemaFixtures.Own.class, SchemaFixtures.Escape.class);

        assertThatThrownBy(() -> validator.validate(MANIFEST, getClass().getClassLoader(), List.of("x.Thing"), Map.of()))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessage("plugin com.crm.x maps database objects outside its schema 'plugin_x': table public.users");
        assertThat(events).containsExactly("start [x.Thing]", "close");
    }

    @Test
    void trial_mapping_failure_is_reported_and_factory_still_closed() {
        trialFailure = new IllegalStateException("wrapper",
            new IllegalArgumentException("Schema-validation: missing column [missing_col]"));

        assertThatThrownBy(() -> validator.validate(MANIFEST, getClass().getClassLoader(), List.of("x.Thing"), Map.of()))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("missing_col");
        assertThat(events).containsExactly("start [x.Thing]", "close");
    }

    @Test
    void schema_violation_raised_inside_the_build_is_rethrown_as_is() {
        InvalidPluginPackageException violation = new InvalidPluginPackageException(
            "plugin com.crm.x maps database objects outside its schema 'plugin_x': table public.users");
        trialFailure = new IllegalStateException("Unable to build Hibernate SessionFactory", violation);

        assertThatThrownBy(() -> validator.validate(MANIFEST, getClass().getClassLoader(), List.of("x.Thing"), Map.of()))
            .isSameAs(violation);
        assertThat(events).containsExactly("start [x.Thing]", "close");
    }

    @Test
    void no_entities_means_no_trial_factory() {
        validator.validate(MANIFEST, getClass().getClassLoader(), List.of(), Map.of());

        assertThat(events).isEmpty();
    }
}
