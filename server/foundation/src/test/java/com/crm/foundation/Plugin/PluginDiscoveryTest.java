package com.crm.foundation.Plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDiscoveryTest {

    @TempDir
    Path pluginDir;

    private static final String VALID_YAML = """
        id: com.crm.demo
        version: 1.0.0
        schema: demo
        entry: com.crm.demo.DemoPlugin
        """;

    private void writeZip(String name, String manifestContent, String... extraEntries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(pluginDir.resolve(name)))) {
            if (manifestContent != null) {
                out.putNextEntry(new ZipEntry("plugin.yaml"));
                out.write(manifestContent.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (String entryName : extraEntries) {
                out.putNextEntry(new ZipEntry(entryName));
                out.closeEntry();
            }
        }
    }

    private PluginDiscovery discoveryFor(Path dir) {
        return new PluginDiscovery(dir.toString(), new PluginZipValidator(), new PluginManifestParser());
    }

    @Test
    void should_discover_valid_plugin_with_parsed_manifest() throws IOException {
        writeZip("demo.zip", VALID_YAML, "lib/demo-1.0.0.jar");

        List<PluginDiscoveryResult> results = discoveryFor(pluginDir).scan();

        assertThat(results).hasSize(1);
        PluginDiscoveryResult result = results.get(0);
        assertThat(result.isValid()).isTrue();
        assertThat(result.manifest().id()).isEqualTo("com.crm.demo");
        assertThat(result.packagePath()).endsWith("demo.zip");
    }

    @Test
    void should_reject_invalid_package_with_reason_and_keep_scanning_others() throws IOException {
        writeZip("good.zip", VALID_YAML);
        writeZip("traversal.zip", VALID_YAML, "../evil.sh");
        writeZip("nomanifest.zip", null, "lib/x.jar");
        writeZip("badfields.zip", "version: 1.0.0\n");

        List<PluginDiscoveryResult> results = discoveryFor(pluginDir).scan();

        assertThat(results).hasSize(4);
        assertThat(results).filteredOn(PluginDiscoveryResult::isValid).hasSize(1);

        List<PluginDiscoveryResult> rejected = results.stream()
            .filter(r -> !r.isValid())
            .toList();
        assertThat(rejected).allSatisfy(r -> assertThat(r.rejectionReason()).isNotBlank());
        assertThat(rejected).extracting(PluginDiscoveryResult::packagePath)
            .anySatisfy(p -> assertThat(p).endsWith("traversal.zip"))
            .anySatisfy(p -> assertThat(p).endsWith("nomanifest.zip"))
            .anySatisfy(p -> assertThat(p).endsWith("badfields.zip"));
    }

    @Test
    void should_ignore_files_that_are_not_zip_packages() throws IOException {
        writeZip("demo.zip", VALID_YAML);
        Files.writeString(pluginDir.resolve("README.md"), "not a plugin");
        Files.createDirectory(pluginDir.resolve("unpacked-stuff"));

        List<PluginDiscoveryResult> results = discoveryFor(pluginDir).scan();

        assertThat(results).hasSize(1);
    }

    @Test
    void should_return_empty_list_when_plugin_directory_does_not_exist() {
        PluginDiscovery discovery = discoveryFor(pluginDir.resolve("does-not-exist"));

        assertThat(discovery.scan()).isEmpty();
    }
}
