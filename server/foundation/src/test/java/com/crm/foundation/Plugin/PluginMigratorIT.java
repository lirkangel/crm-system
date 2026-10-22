package com.crm.foundation.Plugin;

import com.crm.foundation.config.TestContainersConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves T015a's acceptance: plugin Flyway scripts run against the plugin's
 * own Postgres schema — tables land there, not in {@code public}.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginMigratorIT {

    @Autowired
    private PluginMigrator migrator;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private Path demoZip() throws IOException {
        Path zip = tempDir.resolve("demo.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("plugin.yaml"));
            out.write("id: com.crm.demo\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("db/V1__init.sql"));
            out.write("""
                CREATE TABLE demo_thing (
                    id UUID PRIMARY KEY,
                    name TEXT NOT NULL
                );
                """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    @Test
    void should_create_plugin_tables_in_plugin_schema_not_public() throws IOException {
        PluginManifest manifest = new PluginManifest(
            "com.crm.demo", "1.0.0", Map.of(), Map.of(), List.of(), List.of(),
            "plugin_demo", "demo.Entry");

        migrator.migrate(demoZip(), manifest);

        Integer inPluginSchema = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'plugin_demo' AND table_name = 'demo_thing'",
            Integer.class);
        Integer inPublic = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'demo_thing'",
            Integer.class);

        assertThat(inPluginSchema).isEqualTo(1);
        assertThat(inPublic).isZero();

        // Flyway bookkeeping also lives inside the plugin schema
        Integer historyInPluginSchema = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'plugin_demo' AND table_name = 'flyway_schema_history'",
            Integer.class);
        assertThat(historyInPluginSchema).isEqualTo(1);
    }
}
