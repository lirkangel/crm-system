package com.crm.hotel;

import com.crm.foundation.Plugin.PluginActivator;

/**
 * Entry point named by {@code entry} in {@code plugin.yaml}. Everything the hotel
 * plugin does is wired by component scan of this package in its child context,
 * so there is nothing to set up when the plugin loads.
 */
public class HotelPlugin implements PluginActivator {

    @Override
    public void onLoad() {
        // Intentionally empty: beans, repositories and controllers are discovered by scanning com.crm.hotel.
    }
}
