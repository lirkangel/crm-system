package com.crm.foundation.Plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads a plugin's code (T015): extracts {@code lib/*.jar} from the (already
 * validated) package into a working directory, builds a
 * {@link ParentLastClassLoader} over them ({@link #createClassLoader}), and
 * instantiates the manifest's declared {@code entry} class as a
 * {@link PluginActivator} ({@link #instantiateEntry}).
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    /** Builds the plugin's classloader and instantiates its entry (classloader + entry in one step). */
    public PluginActivator loadEntrypoint(Path zipPath, PluginManifest manifest) {
        return instantiateEntry(createClassLoader(zipPath, manifest), manifest);
    }

    /** Extracts {@code lib/*.jar} and builds a {@link ParentLastClassLoader} over them. */
    public ParentLastClassLoader createClassLoader(Path zipPath, PluginManifest manifest) {
        List<URL> jarUrls = extractLibJars(zipPath, manifest.id());
        if (jarUrls.isEmpty()) {
            throw new InvalidPluginPackageException(
                "package contains no lib/*.jar to load for plugin " + manifest.id());
        }
        return new ParentLastClassLoader(
            manifest.id(), jarUrls.toArray(URL[]::new), getClass().getClassLoader());
    }

    private List<URL> extractLibJars(Path zipPath, String pluginId) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Path workDir = Files.createTempDirectory("plugin-" + pluginId + "-");
            List<URL> urls = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || !entry.getName().startsWith("lib/")
                        || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                Path target = workDir.resolve(entry.getName().substring("lib/".length()));
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
                urls.add(toUrl(target));
            }
            log.debug("Extracted {} jar(s) for plugin {} into {}", urls.size(), pluginId, workDir);
            return urls;
        } catch (IOException e) {
            throw new InvalidPluginPackageException(
                "failed to extract plugin jars from " + zipPath.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Unrepresentable jar path: " + path, e);
        }
    }

    /** Loads the manifest's {@code entry} class from {@code classLoader} and instantiates it. */
    public PluginActivator instantiateEntry(ClassLoader classLoader, PluginManifest manifest) {
        Class<?> entryClass;
        try {
            entryClass = classLoader.loadClass(manifest.entry());
        } catch (ClassNotFoundException e) {
            throw new InvalidPluginPackageException(
                "entry class " + manifest.entry() + " not found in plugin " + manifest.id(), e);
        }
        if (!PluginActivator.class.isAssignableFrom(entryClass)) {
            throw new InvalidPluginPackageException(
                "entry class " + manifest.entry() + " does not implement PluginActivator");
        }
        try {
            return (PluginActivator) entryClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new InvalidPluginPackageException(
                "cannot instantiate entry class " + manifest.entry()
                    + " (public no-arg constructor required): " + e.getMessage(), e);
        }
    }
}
