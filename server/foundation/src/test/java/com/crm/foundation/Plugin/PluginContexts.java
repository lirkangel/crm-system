package com.crm.foundation.Plugin;

import org.springframework.context.ConfigurableApplicationContext;

/** Test accessor for the package-private {@link PluginHost#runningContext(String)}. */
public final class PluginContexts {

    private PluginContexts() {
    }

    /** The running child context of a plugin; fails when the plugin is not running. */
    public static ConfigurableApplicationContext running(PluginHost host, String pluginId) {
        return host.runningContext(pluginId)
            .orElseThrow(() -> new AssertionError("plugin " + pluginId + " is not running"));
    }
}
