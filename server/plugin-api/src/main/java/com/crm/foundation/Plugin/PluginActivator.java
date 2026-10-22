package com.crm.foundation.Plugin;

/**
 * Plugin entrypoint contract (spec §6: the class named by {@code entry} in
 * {@code plugin.yaml} implements this). Loaded through the plugin's own
 * parent-last classloader; this interface itself always resolves from the
 * foundation classloader so instances are castable across the boundary.
 */
public interface PluginActivator {

    /** Invoked once after the plugin's classloader is set up. */
    void onLoad();
}
