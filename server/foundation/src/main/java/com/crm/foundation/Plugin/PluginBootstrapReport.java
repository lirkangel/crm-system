package com.crm.foundation.Plugin;

import java.util.List;

/** Result of {@link PluginBootstrap}: one entry per valid discovered plugin package. */
public record PluginBootstrapReport(List<BootstrappedPlugin> plugins) {

    public PluginBootstrapReport {
        plugins = List.copyOf(plugins);
    }

    /** Plugins whose entities and classloaders join Foundation's persistence unit. */
    public List<BootstrappedPlugin> ready() {
        return plugins.stream()
            .filter(plugin -> plugin.status() == BootstrappedPlugin.Status.READY)
            .toList();
    }
}
