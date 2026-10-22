package com.crm.foundation.Plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginManifestParserTest {

    private final PluginManifestParser parser = new PluginManifestParser();

    private static final String VALID_YAML = """
        id: com.crm.demo
        version: 1.0.0
        display-name:
          vi: "Demo"
          en: "Demo plugin"
        depends-on:
          foundation: ">=0.1.0,<2.0.0"
        permissions-declared:
          - demo.thing.read
        foundation-services-used:
          - persistence
        schema: demo
        entry: com.crm.demo.DemoPlugin
        """;

    @Test
    void should_parse_valid_manifest_with_all_fields() {
        PluginManifest manifest = parser.parse(VALID_YAML);

        assertThat(manifest.id()).isEqualTo("com.crm.demo");
        assertThat(manifest.version()).isEqualTo("1.0.0");
        assertThat(manifest.displayName()).containsEntry("vi", "Demo").containsEntry("en", "Demo plugin");
        assertThat(manifest.dependsOn()).containsEntry("foundation", ">=0.1.0,<2.0.0");
        assertThat(manifest.permissionsDeclared()).containsExactly("demo.thing.read");
        assertThat(manifest.foundationServicesUsed()).containsExactly("persistence");
        assertThat(manifest.schema()).isEqualTo("demo");
        assertThat(manifest.entry()).isEqualTo("com.crm.demo.DemoPlugin");
    }

    @Test
    void should_default_optional_collections_to_empty_when_absent() {
        PluginManifest manifest = parser.parse("""
            id: com.crm.demo
            version: 1.0.0
            schema: demo
            entry: com.crm.demo.DemoPlugin
            """);

        assertThat(manifest.displayName()).isEmpty();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest.permissionsDeclared()).isEmpty();
        assertThat(manifest.foundationServicesUsed()).isEmpty();
    }

    @Test
    void should_reject_manifest_missing_id() {
        assertThatThrownBy(() -> parser.parse("""
            version: 1.0.0
            schema: demo
            entry: com.crm.demo.DemoPlugin
            """))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("id");
    }

    @Test
    void should_reject_manifest_missing_version() {
        assertThatThrownBy(() -> parser.parse("""
            id: com.crm.demo
            schema: demo
            entry: com.crm.demo.DemoPlugin
            """))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("version");
    }

    @Test
    void should_reject_manifest_missing_schema() {
        assertThatThrownBy(() -> parser.parse("""
            id: com.crm.demo
            version: 1.0.0
            entry: com.crm.demo.DemoPlugin
            """))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("schema");
    }

    @Test
    void should_reject_manifest_missing_entry() {
        assertThatThrownBy(() -> parser.parse("""
            id: com.crm.demo
            version: 1.0.0
            schema: demo
            """))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("entry");
    }

    @Test
    void should_reject_blank_required_field() {
        assertThatThrownBy(() -> parser.parse("""
            id: "  "
            version: 1.0.0
            schema: demo
            entry: com.crm.demo.DemoPlugin
            """))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("id");
    }

    @Test
    void should_reject_unparseable_yaml_with_clear_reason() {
        assertThatThrownBy(() -> parser.parse("{ this is : not [ valid yaml"))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("plugin.yaml");
    }

    @Test
    void should_reject_yaml_that_is_not_a_mapping() {
        assertThatThrownBy(() -> parser.parse("- just\n- a\n- list"))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("plugin.yaml");
    }
}
