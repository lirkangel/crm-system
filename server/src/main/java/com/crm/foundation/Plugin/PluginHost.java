package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Service.PluginRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Startup orchestrator for the plugin lifecycle (T014a): scans the plugin
 * directory, registers newly discovered plugins, activates loadable ones, and
 * marks failures {@code LOAD_FAILED} — strictly per-plugin, so one broken
 * package can never prevent the others (or the application) from starting.
 *
 * <p>{@code DISABLED} and {@code UNINSTALL_PENDING} plugins are skipped.
 * Classloading (T015) and per-schema migration (T015a) slot into
 * {@link #loadPlugin} as the next steps of the pipeline.
 */
@Component
public class PluginHost implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginHost.class);

    private final PluginDiscovery discovery;
    private final PluginRegistryService registryService;

    public PluginHost(PluginDiscovery discovery, PluginRegistryService registryService) {
        this.discovery = discovery;
        this.registryService = registryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        startPlugins();
    }

    public void startPlugins() {
        for (PluginDiscoveryResult result : discovery.scan()) {
            if (result.isValid()) {
                loadIsolated(result.manifest());
            } else {
                // No parseable manifest — no plugin id to record against; discovery already logged the reason
                log.warn("Skipping rejected plugin package {}: {}", result.packagePath(), result.rejectionReason());
            }
        }
    }

    private void loadIsolated(PluginManifest manifest) {
        try {
            loadPlugin(manifest);
        } catch (Exception e) {
            log.error("Plugin {} failed to load: {}", manifest.id(), e.getMessage(), e);
            markLoadFailedQuietly(manifest.id(), e.getMessage());
        }
    }

    private void loadPlugin(PluginManifest manifest) {
        Optional<PluginRegistry> existing = registryService.findByPluginId(manifest.id());
        if (existing.isPresent() && isSkipped(existing.get())) {
            log.info("Skipping plugin {} (state {})", manifest.id(), existing.get().getState());
            return;
        }
        if (existing.isEmpty()) {
            registryService.register(manifest);
        }
        registryService.activate(manifest.id());
        log.info("Plugin {} v{} active", manifest.id(), manifest.version());
    }

    private static boolean isSkipped(PluginRegistry entry) {
        return PluginState.DISABLED.name().equals(entry.getState())
            || PluginState.UNINSTALL_PENDING.name().equals(entry.getState());
    }

    private void markLoadFailedQuietly(String pluginId, String reason) {
        try {
            registryService.markLoadFailed(pluginId, reason);
        } catch (Exception e) {
            // Startup resilience trumps bookkeeping: never let failure-marking abort the host
            log.error("Could not mark plugin {} as LOAD_FAILED: {}", pluginId, e.getMessage(), e);
        }
    }
}
