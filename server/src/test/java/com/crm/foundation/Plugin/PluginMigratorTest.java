package com.crm.foundation.Plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * DB-free coverage of {@link PluginMigrator}: script extraction and the
 * no-migrations fast path. Real schema isolation is proven in
 * {@code PluginMigratorIT} (Testcontainers).
 */
class PluginMigratorTest {

    @TempDir
    Path tempDir;

    // DataSource is never touched on the paths under test
    private final PluginMigrator migrator = new PluginMigrator(null);

    private static PluginManifest manifest(String schema) {
        return new PluginManifest(
            "com.crm.demo", "1.0.0", Map.of(), Map.of(), List.of(), List.of(), schema, "demo.Entry");
    }

    private Path zipWith(String... entries) throws IOException {
        Path zip = tempDir.resolve("demo.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entryName : entries) {
                out.putNextEntry(new ZipEntry(entryName));
                out.write(("-- " + entryName).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void should_extract_only_sql_scripts_under_db_directory() throws IOException {
        Path zip = zipWith("plugin.yaml", "db/V1__init.sql", "db/V2__more.sql", "lib/x.jar", "db/notes.txt");

        Path scriptsDir = migrator.extractDbScripts(zip, "com.crm.demo");

        assertThat(scriptsDir).isNotNull();
        try (var files = Files.list(scriptsDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrder("V1__init.sql", "V2__more.sql");
        }
    }

    @Test
    void should_return_null_when_package_has_no_db_scripts() throws IOException {
        Path zip = zipWith("plugin.yaml", "lib/x.jar");

        assertThat(migrator.extractDbScripts(zip, "com.crm.demo")).isNull();
    }

    @Test
    void should_skip_migration_entirely_when_no_db_scripts() throws IOException {
        Path zip = zipWith("plugin.yaml", "lib/x.jar");

        // Null DataSource: would NPE if Flyway were invoked
        migrator.migrate(zip, manifest("demo"));
    }
}
