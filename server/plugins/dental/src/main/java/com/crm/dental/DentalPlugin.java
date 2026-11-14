package com.crm.dental;

import com.crm.foundation.Plugin.PluginActivator;

/**
 * Entry point named by {@code entry} in {@code plugin.yaml}. Everything the dental plugin does is wired by
 * component scan of this package in its child context, so there is nothing to set up when the plugin loads.
 */
public class DentalPlugin implements PluginActivator {

    @Override
    public void onLoad() {
        // Intentionally empty: beans, repositories and controllers are discovered by scanning com.crm.dental.
    }
}
