package com.crm.foundation.support;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.Plugin.PluginActivator;
import jakarta.persistence.Entity;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a real plugin ZIP at test time: compiles the given Java sources,
 * packs them into {@code lib/entry.jar}, and writes {@code plugin.yaml} plus
 * any {@code db/*.sql} migrations.
 *
 * <p>Sources compile against the code-source locations (jar or classes dir)
 * of marker classes — by default {@link PluginActivator}, {@link Entity} and
 * {@link CommonResponse}; {@link #withClasspathOf} adds more. The JVM's
 * {@code java.class.path} is deliberately not used: under Surefire
 * {@code forkCount=0} it is Maven's own classpath, not the test classpath.
 */
public final class PluginPackageBuilder {

    private final String id;
    private String version = "1.0.0";
    private String schema;
    private String entry;
    private final Set<Class<?>> classpathMarkers =
        new LinkedHashSet<>(List.of(PluginActivator.class, Entity.class, CommonResponse.class));
    /** Fully-qualified class name → Java source. */
    private final Map<String, String> sources = new LinkedHashMap<>();
    /** File name under {@code db/} → SQL. */
    private final Map<String, String> migrations = new LinkedHashMap<>();

    private PluginPackageBuilder(String id) {
        this.id = id;
    }

    public static PluginPackageBuilder plugin(String id) {
        return new PluginPackageBuilder(id);
    }

    public PluginPackageBuilder version(String version) {
        this.version = version;
        return this;
    }

    public PluginPackageBuilder schema(String schema) {
        this.schema = schema;
        return this;
    }

    /** Manifest {@code entry}: fully-qualified activator class name. */
    public PluginPackageBuilder entry(String entry) {
        this.entry = entry;
        return this;
    }

    public PluginPackageBuilder source(String className, String javaSource) {
        sources.put(className, javaSource);
        return this;
    }

    /** Adds {@code db/<fileName>} (e.g. {@code V1__init.sql}). */
    public PluginPackageBuilder migration(String fileName, String sql) {
        migrations.put(fileName, sql);
        return this;
    }

    /** Adds the code sources of {@code markers} to the compile classpath (on top of the defaults). */
    public PluginPackageBuilder withClasspathOf(Class<?>... markers) {
        classpathMarkers.addAll(List.of(markers));
        return this;
    }

    /** Writes the plugin package to {@code zip} and returns it. */
    public Path buildTo(Path zip) throws IOException {
        Path work = Files.createTempDirectory("plugin-build-");
        Path jar = packJar(compile(work), work.resolve("entry.jar"));
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            putEntry(out, "plugin.yaml", manifestYaml().getBytes(StandardCharsets.UTF_8));
            putEntry(out, "lib/entry.jar", Files.readAllBytes(jar));
            for (Map.Entry<String, String> migration : migrations.entrySet()) {
                putEntry(out, "db/" + migration.getKey(), migration.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return zip;
    }

    private String manifestYaml() {
        return """
            id: %s
            version: %s
            display-name:
              en: "%s"
            schema: %s
            entry: %s
            """.formatted(id, version, id, schema, entry);
    }

    private Path compile(Path work) throws IOException {
        Path srcDir = Files.createDirectories(work.resolve("src"));
        Path classesDir = Files.createDirectories(work.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("-cp", compileClasspath(), "-d", classesDir.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = srcDir.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        if (result != 0) {
            throw new IllegalStateException("plugin " + id + " sources failed to compile");
        }
        return classesDir;
    }

    private String compileClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> marker : classpathMarkers) {
            CodeSource codeSource = marker.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                throw new IllegalStateException("no code source for classpath marker " + marker.getName());
            }
            try {
                entries.add(Path.of(codeSource.getLocation().toURI()).toString());
            } catch (URISyntaxException e) {
                throw new IllegalStateException("bad code source for classpath marker " + marker.getName(), e);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Packs compiled classes into a jar, directory entries included: Spring's
     * classpath scanning finds packages inside a jar only through them (as in
     * jars built by Maven).
     */
    private static Path packJar(Path classesDir, Path jar) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar));
             Stream<Path> paths = Files.walk(classesDir)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                if (path.equals(classesDir)) {
                    continue;
                }
                String name = classesDir.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    out.putNextEntry(new ZipEntry(name + "/"));
                    out.closeEntry();
                } else {
                    putEntry(out, name, Files.readAllBytes(path));
                }
            }
        }
        return jar;
    }

    private static void putEntry(ZipOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.closeEntry();
    }
}
