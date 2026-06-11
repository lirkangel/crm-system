package com.crm.foundation.Plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginZipValidatorTest {

    private final PluginZipValidator validator = new PluginZipValidator();

    @TempDir
    Path tempDir;

    private Path zipWithEntries(String name, String... entryNames) throws IOException {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entryName : entryNames) {
                out.putNextEntry(new ZipEntry(entryName));
                out.write(("content of " + entryName).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void should_return_manifest_content_for_safe_zip_with_plugin_yaml() throws IOException {
        Path zip = zipWithEntries("valid.zip", "plugin.yaml", "lib/demo-1.0.0.jar", "db/V1__init.sql");

        String manifest = validator.validateAndReadManifest(zip);

        assertThat(manifest).isEqualTo("content of plugin.yaml");
    }

    @Test
    void should_reject_entry_with_parent_directory_traversal() throws IOException {
        Path zip = zipWithEntries("traversal.zip", "plugin.yaml", "../../etc/evil.sh");

        assertThatThrownBy(() -> validator.validateAndReadManifest(zip))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("../../etc/evil.sh");
    }

    @Test
    void should_reject_entry_with_embedded_traversal_segment() throws IOException {
        Path zip = zipWithEntries("embedded.zip", "plugin.yaml", "lib/../../outside.jar");

        assertThatThrownBy(() -> validator.validateAndReadManifest(zip))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("lib/../../outside.jar");
    }

    @Test
    void should_reject_entry_with_absolute_path() throws IOException {
        Path zip = zipWithEntries("absolute.zip", "plugin.yaml", "/etc/passwd");

        assertThatThrownBy(() -> validator.validateAndReadManifest(zip))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("/etc/passwd");
    }

    @Test
    void should_reject_entry_with_backslash_traversal() throws IOException {
        Path zip = zipWithEntries("backslash.zip", "plugin.yaml", "..\\evil.bat");

        assertThatThrownBy(() -> validator.validateAndReadManifest(zip))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("evil.bat");
    }

    @Test
    void should_reject_zip_without_plugin_yaml() throws IOException {
        Path zip = zipWithEntries("nomanifest.zip", "lib/demo-1.0.0.jar");

        assertThatThrownBy(() -> validator.validateAndReadManifest(zip))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("plugin.yaml");
    }

    @Test
    void should_reject_file_that_is_not_a_zip() throws IOException {
        Path notAZip = tempDir.resolve("garbage.zip");
        Files.writeString(notAZip, "this is not a zip archive");

        assertThatThrownBy(() -> validator.validateAndReadManifest(notAZip))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("garbage.zip");
    }
}
