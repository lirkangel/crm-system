package com.crm.foundation.Plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves real classloader isolation by compiling a plugin entrypoint at test
 * runtime, packaging it as {@code lib/*.jar} inside a plugin ZIP, and loading
 * it through {@link PluginLoader}.
 */
class PluginLoaderTest {

    @TempDir
    Path tempDir;

    private final PluginLoader loader = new PluginLoader();

    private static PluginManifest manifest(String entry) {
        return new PluginManifest(
            "com.crm.demo", "1.0.0", Map.of(), Map.of(), List.of(), List.of(), "demo", entry);
    }

    /** Compiles {@code source} (declaring {@code className}) and returns the class-output dir. */
    private Path compile(String className, String source) throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path outDir = Files.createDirectories(tempDir.resolve("classes"));
        Path srcFile = srcDir.resolve(className.substring(className.lastIndexOf('.') + 1) + ".java");
        Files.writeString(srcFile, source);

        // Plugin API (PluginActivator) comes from the app's compiled classes
        String apiClasspath;
        try {
            apiClasspath = Path.of(
                PluginActivator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null,
            "-cp", apiClasspath, "-d", outDir.toString(), srcFile.toString());
        assertThat(result).as("compilation of test plugin source").isZero();
        return outDir;
    }

    private Path jarOf(Path classesDir) throws IOException {
        Path jar = tempDir.resolve("entry.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classesDir)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry(classesDir.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    private Path pluginZip(Path entryJar) throws IOException {
        Path zip = tempDir.resolve("demo.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("plugin.yaml"));
            out.write("id: com.crm.demo\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("lib/entry.jar"));
            out.write(Files.readAllBytes(entryJar));
            out.closeEntry();
        }
        return zip;
    }

    private Path zipWithActivator(String className, String body) throws IOException {
        return pluginZip(jarOf(compile(className, body)));
    }

    @Test
    void should_load_and_instantiate_entrypoint_in_isolated_classloader() throws IOException {
        Path zip = zipWithActivator("demo.DemoPlugin", """
            package demo;
            public class DemoPlugin implements com.crm.foundation.Plugin.PluginActivator {
                public void onLoad() {
                    System.setProperty("plugin.loader.test.loaded", "yes");
                }
            }
            """);

        PluginActivator activator = loader.loadEntrypoint(zip, manifest("demo.DemoPlugin"));

        // Plugin class lives in its own loader; the shared API type comes from the parent
        assertThat(activator.getClass().getClassLoader()).isNotEqualTo(getClass().getClassLoader());
        assertThat(activator).isInstanceOf(PluginActivator.class);

        System.clearProperty("plugin.loader.test.loaded");
        activator.onLoad();
        assertThat(System.getProperty("plugin.loader.test.loaded")).isEqualTo("yes");
    }

    @Test
    void should_reject_when_entry_class_is_missing_from_package() throws IOException {
        Path zip = zipWithActivator("demo.DemoPlugin", """
            package demo;
            public class DemoPlugin implements com.crm.foundation.Plugin.PluginActivator {
                public void onLoad() {}
            }
            """);

        assertThatThrownBy(() -> loader.loadEntrypoint(zip, manifest("demo.NoSuchPlugin")))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("demo.NoSuchPlugin");
    }

    @Test
    void should_reject_entry_class_that_is_not_a_plugin_activator() throws IOException {
        Path zip = zipWithActivator("demo.NotAnActivator", """
            package demo;
            public class NotAnActivator {
            }
            """);

        assertThatThrownBy(() -> loader.loadEntrypoint(zip, manifest("demo.NotAnActivator")))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("PluginActivator");
    }

    @Test
    void should_reject_entry_class_without_accessible_no_arg_constructor() throws IOException {
        Path zip = zipWithActivator("demo.NoDefaultCtor", """
            package demo;
            public class NoDefaultCtor implements com.crm.foundation.Plugin.PluginActivator {
                public NoDefaultCtor(String required) {}
                public void onLoad() {}
            }
            """);

        assertThatThrownBy(() -> loader.loadEntrypoint(zip, manifest("demo.NoDefaultCtor")))
            .isInstanceOf(InvalidPluginPackageException.class)
            .hasMessageContaining("demo.NoDefaultCtor");
    }
}
