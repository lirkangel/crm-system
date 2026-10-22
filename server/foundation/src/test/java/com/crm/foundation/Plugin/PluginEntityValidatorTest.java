package com.crm.foundation.Plugin;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginEntityValidatorTest {

    // Stand-ins for plugin entities. Deliberately not @Entity: they live under
    // Foundation's package, so Boot's entity scan would add them to the real
    // persistence unit of every @SpringBootTest. The validator only reads the
    // table annotations.

    @Table(schema = "plugin_x", name = "own")
    static class Own {
        private java.util.UUID id;
    }

    @Table(schema = "public", name = "users")
    static class Escape {
        private java.util.UUID id;
    }

    @Table(name = "no_schema")
    static class NoSchema {
        private java.util.UUID id;
    }

    static class NoTable {
        private java.util.UUID id;
    }

    @Table(schema = "plugin_x", name = "tagged")
    static class EscapingCollection {
        private java.util.UUID id;
        @ElementCollection
        @CollectionTable(schema = "public", name = "tags")
        private Set<String> tags;
    }

    private static final PluginManifest MANIFEST = new PluginManifest(
        "com.crm.x", "1.0.0", Map.of(), Map.of(), List.of(), List.of(), "plugin_x", "x.Entry", "x");

    private final List<String> events = new ArrayList<>();
    private RuntimeException trialFailure;

    /** Validator whose trial factory is a double recording start and close. */
    private final PluginEntityValidator validator = new PluginEntityValidator(null, null, null) {
        @Override
        LocalContainerEntityManagerFactoryBean trialFactory(
                PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames) {
            return new LocalContainerEntityManagerFactoryBean() {
                @Override
                public void afterPropertiesSet() {
                    events.add("start " + entityClassNames);
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

    private void validate(Class<?>... entities) {
        validator.validate(MANIFEST, getClass().getClassLoader(),
            java.util.Arrays.stream(entities).map(Class::getName).toList());
    }

    @Test
    void entities_in_own_schema_pass_and_trial_factory_is_closed() {
        assertThatCode(() -> validate(Own.class)).doesNotThrowAnyException();

        assertThat(events).containsExactly("start [" + Own.class.getName() + "]", "close");
    }

    @Test
    void entity_in_another_schema_is_rejected_before_trial_mapping() {
        assertThatThrownBy(() -> validate(Own.class, Escape.class))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining(Escape.class.getName())
            .hasMessageContaining("schema 'public'")
            .hasMessageContaining("plugin_x");
        assertThat(events).isEmpty();
    }

    @Test
    void missing_schema_or_table_counts_as_mismatch() {
        assertThatThrownBy(() -> validate(NoSchema.class))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("schema ''");
        assertThatThrownBy(() -> validate(NoTable.class))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("@Table missing");
    }

    @Test
    void collection_table_outside_schema_is_rejected() {
        assertThatThrownBy(() -> validate(EscapingCollection.class))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("@CollectionTable tags has schema 'public'");
    }

    @Test
    void trial_mapping_failure_is_reported_and_factory_still_closed() {
        trialFailure = new IllegalStateException("wrapper",
            new IllegalArgumentException("Schema-validation: missing column [missing_col]"));

        assertThatThrownBy(() -> validate(Own.class))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("missing_col");
        assertThat(events).containsExactly("start [" + Own.class.getName() + "]", "close");
    }

    @Test
    void no_entities_means_no_trial_factory() {
        validator.validate(MANIFEST, getClass().getClassLoader(), List.of());

        assertThat(events).isEmpty();
    }
}
