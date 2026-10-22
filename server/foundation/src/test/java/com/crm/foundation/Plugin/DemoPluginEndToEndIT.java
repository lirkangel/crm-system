package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Service.PluginRegistryService;
import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T015b: a minimal demo plugin ZIP (manifest + compiled entrypoint jar +
 * Flyway script) dropped into the plugin directory is discovered, registered,
 * migrated into its own schema, and ACTIVE after application startup — the
 * full T013–T015a pipeline end-to-end.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class DemoPluginEndToEndIT {

    private static final String PLUGIN_ID = "com.crm.demo.e2e";
    private static final String SCHEMA = "plugin_demo_e2e";

    @Autowired
    private PluginRegistryService registryService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Builds the demo plugin package before the context (and PluginHost) starts. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("e2e-plugins-");
        buildDemoPluginZip(pluginDir.resolve("demo-e2e.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private static void buildDemoPluginZip(Path zip) throws IOException {
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .schema(SCHEMA)
            .entry("demo.DemoE2ePlugin")
            .source("demo.DemoE2ePlugin", """
                package demo;
                public class DemoE2ePlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                        System.setProperty("demo.e2e.plugin.loaded", "yes");
                    }
                }
                """)
            .migration("V1__init.sql", """
                CREATE TABLE demo_e2e_item (
                    id UUID PRIMARY KEY,
                    label TEXT NOT NULL
                );
                """)
            .buildTo(zip);
    }

    @Test
    void demo_plugin_is_active_with_schema_present_after_startup() {
        PluginRegistry entry = registryService.findByPluginId(PLUGIN_ID).orElseThrow();
        assertThat(entry.getState()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(entry.getSchemaName()).isEqualTo(SCHEMA);
        assertThat(entry.getLastLoadedAt()).isNotNull();
        assertThat(entry.getErrorMessage()).isNull();

        Integer migratedTable = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'demo_e2e_item'",
            Integer.class, SCHEMA);
        assertThat(migratedTable).isEqualTo(1);

        assertThat(System.getProperty("demo.e2e.plugin.loaded")).isEqualTo("yes");
    }
}
