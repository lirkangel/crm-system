package com.crm.foundation.dental;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Plugin.PluginState;
import com.crm.foundation.Service.PluginRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Enabling the public route without DENTAL_FIELD_KEY must stop dental from starting, and only dental. */
class DentalPublicWithoutKeyIT extends DentalITSupport {

    @Autowired
    private PluginRegistryService registryService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> dentalPluginDirectory().toString());
        registry.add("dental.public.enabled", () -> "true");
        registry.add("dental.field-key", () -> "");
    }

    @Test
    void public_enabled_without_key_fails_plugin_load() {
        PluginRegistry dental = registryService.findByPluginId(DENTAL_PLUGIN_ID).orElseThrow();
        assertThat(dental.getState()).isEqualTo(PluginState.LOAD_FAILED.name());

        assertThat(registryService.findByPluginId(HOTEL_PLUGIN_ID).orElseThrow().getState())
            .isEqualTo(PluginState.ACTIVE.name());
    }
}
