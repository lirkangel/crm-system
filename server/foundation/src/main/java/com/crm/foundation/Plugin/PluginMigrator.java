package com.crm.foundation.Plugin;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs a plugin's Flyway migrations against the plugin's own Postgres schema
 * (T015a): {@code db/V*.sql} scripts are extracted from the (validated)
 * package and applied with the manifest's {@code schema} as the default —
 * plugin tables and Flyway history never touch {@code public}.
 */
@Component
public class PluginMigrator {

    private static final Logger log = LoggerFactory.getLogger(PluginMigrator.class);
    private static final String DB_PREFIX = "db/";

    private final DataSource dataSource;

    public PluginMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void migrate(Path zipPath, PluginManifest manifest) {
        Path scriptsDir = extractDbScripts(zipPath, manifest.id());
        if (scriptsDir == null) {
            log.info("Plugin {} ships no db/ migrations; skipping", manifest.id());
            return;
        }
        Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema(manifest.schema())
            .schemas(manifest.schema())
            .createSchemas(true)
            .locations("filesystem:" + scriptsDir)
            .load()
            .migrate();
        log.info("Plugin {} migrated into schema {}", manifest.id(), manifest.schema());
    }

    /**
     * Extracts {@code db/*.sql} into a temp dir for Flyway's filesystem
     * resolver. Returns {@code null} when the package ships no migrations.
     */
    Path extractDbScripts(Path zipPath, String pluginId) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Path scriptsDir = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || !entry.getName().startsWith(DB_PREFIX)
                        || !entry.getName().endsWith(".sql")) {
                    continue;
                }
                if (scriptsDir == null) {
                    scriptsDir = Files.createTempDirectory("plugin-db-" + pluginId + "-");
                }
                Path target = scriptsDir.resolve(entry.getName().substring(DB_PREFIX.length()));
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
            return scriptsDir;
        } catch (IOException e) {
            throw new InvalidPluginPackageException(
                "failed to extract db scripts from " + zipPath.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
