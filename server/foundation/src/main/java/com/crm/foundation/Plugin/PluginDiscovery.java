package com.crm.foundation.Plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans the plugin directory ({@code foundation.plugins.directory}) for
 * {@code *.zip} packages and validates each one (T014). Per-package failures
 * are captured as rejected results so one bad package can't block the rest.
 */
@Component
public class PluginDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PluginDiscovery.class);

    private final Path pluginDirectory;
    private final PluginZipValidator zipValidator;
    private final PluginManifestParser manifestParser;

    public PluginDiscovery(
            @Value("${foundation.plugins.directory}") String pluginDirectory,
            PluginZipValidator zipValidator,
            PluginManifestParser manifestParser) {
        this.pluginDirectory = Path.of(pluginDirectory);
        this.zipValidator = zipValidator;
        this.manifestParser = manifestParser;
    }

    public List<PluginDiscoveryResult> scan() {
        if (!Files.isDirectory(pluginDirectory)) {
            log.info("Plugin directory {} does not exist; no plugins to discover", pluginDirectory);
            return List.of();
        }
        try (Stream<Path> files = Files.list(pluginDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .sorted()
                .map(this::validatePackage)
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan plugin directory " + pluginDirectory, e);
        }
    }

    private PluginDiscoveryResult validatePackage(Path zip) {
        String packagePath = zip.toAbsolutePath().toString();
        try {
            String manifestYaml = zipValidator.validateAndReadManifest(zip);
            PluginManifest manifest = manifestParser.parse(manifestYaml);
            log.info("Discovered plugin {} v{} from {}", manifest.id(), manifest.version(), zip.getFileName());
            return PluginDiscoveryResult.valid(packagePath, manifest);
        } catch (InvalidPluginPackageException e) {
            log.warn("Rejected plugin package {}: {}", zip.getFileName(), e.getMessage());
            return PluginDiscoveryResult.rejected(packagePath, e.getMessage());
        }
    }
}
