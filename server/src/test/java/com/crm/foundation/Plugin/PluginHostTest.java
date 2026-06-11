package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Service.PluginRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginHostTest {

    @Mock
    private PluginRegistryService registryService;

    private static PluginManifest manifest(String id) {
        return new PluginManifest(
            id, "1.0.0", Map.of(), Map.of(), List.of(), List.of(), "demo", id + ".Entry");
    }

    private static PluginRegistry registryRow(String pluginId, PluginState state) {
        PluginRegistry row = new PluginRegistry();
        row.setPluginId(pluginId);
        row.setState(state.name());
        return row;
    }

    /** Subclass doubles — Mockito's inline mock-maker can't instrument concrete classes on this JDK. */
    private final List<String> loadedEntrypoints = new java.util.ArrayList<>();
    private final List<String> failEntrypointFor = new java.util.ArrayList<>();

    private PluginHost hostScanning(PluginDiscoveryResult... results) {
        PluginDiscovery discovery = new PluginDiscovery("unused", new PluginZipValidator(), new PluginManifestParser()) {
            @Override
            public List<PluginDiscoveryResult> scan() {
                return List.of(results);
            }
        };
        PluginLoader loader = new PluginLoader() {
            @Override
            public PluginActivator loadEntrypoint(java.nio.file.Path zip, PluginManifest manifest) {
                if (failEntrypointFor.contains(manifest.id())) {
                    throw new InvalidPluginPackageException("entry class missing for " + manifest.id());
                }
                return () -> loadedEntrypoints.add(manifest.id());
            }
        };
        return new PluginHost(discovery, registryService, loader);
    }

    @Test
    void should_register_and_activate_new_valid_plugin() {
        PluginManifest manifest = manifest("com.crm.demo");
        PluginHost host = hostScanning(PluginDiscoveryResult.valid("/plugins/demo.zip", manifest));
        when(registryService.findByPluginId("com.crm.demo")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).register(manifest);
        verify(registryService).activate("com.crm.demo");
        org.assertj.core.api.Assertions.assertThat(loadedEntrypoints).containsExactly("com.crm.demo");
    }

    @Test
    void should_mark_load_failed_when_entrypoint_loading_fails() {
        PluginManifest manifest = manifest("com.crm.broken");
        failEntrypointFor.add("com.crm.broken");
        PluginHost host = hostScanning(PluginDiscoveryResult.valid("/plugins/broken.zip", manifest));
        when(registryService.findByPluginId("com.crm.broken")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).markLoadFailed(eq("com.crm.broken"), contains("entry class missing"));
        verify(registryService, never()).activate("com.crm.broken");
    }

    @Test
    void should_skip_rejected_package_and_still_load_valid_one() {
        PluginManifest valid = manifest("com.crm.good");
        PluginHost host = hostScanning(
            PluginDiscoveryResult.rejected("/plugins/broken.zip", "unsafe ZIP entry path"),
            PluginDiscoveryResult.valid("/plugins/good.zip", valid));
        when(registryService.findByPluginId("com.crm.good")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).register(valid);
        verify(registryService).activate("com.crm.good");
    }

    @Test
    void should_mark_load_failed_and_continue_when_one_plugin_throws() {
        PluginManifest failing = manifest("com.crm.failing");
        PluginManifest healthy = manifest("com.crm.healthy");
        PluginHost host = hostScanning(
            PluginDiscoveryResult.valid("/plugins/failing.zip", failing),
            PluginDiscoveryResult.valid("/plugins/healthy.zip", healthy));
        when(registryService.findByPluginId("com.crm.failing")).thenReturn(Optional.empty());
        when(registryService.findByPluginId("com.crm.healthy")).thenReturn(Optional.empty());
        when(registryService.register(failing)).thenThrow(new IllegalStateException("schema collision"));

        host.startPlugins();

        verify(registryService).markLoadFailed(eq("com.crm.failing"), contains("schema collision"));
        verify(registryService, never()).activate("com.crm.failing");
        verify(registryService).activate("com.crm.healthy");
    }

    @Test
    void should_not_activate_disabled_plugin() {
        PluginManifest manifest = manifest("com.crm.disabled");
        PluginHost host = hostScanning(PluginDiscoveryResult.valid("/plugins/disabled.zip", manifest));
        when(registryService.findByPluginId("com.crm.disabled"))
            .thenReturn(Optional.of(registryRow("com.crm.disabled", PluginState.DISABLED)));

        host.startPlugins();

        verify(registryService, never()).register(any());
        verify(registryService, never()).activate("com.crm.disabled");
    }

    @Test
    void should_not_activate_uninstall_pending_plugin() {
        PluginManifest manifest = manifest("com.crm.gone");
        PluginHost host = hostScanning(PluginDiscoveryResult.valid("/plugins/gone.zip", manifest));
        when(registryService.findByPluginId("com.crm.gone"))
            .thenReturn(Optional.of(registryRow("com.crm.gone", PluginState.UNINSTALL_PENDING)));

        host.startPlugins();

        verify(registryService, never()).register(any());
        verify(registryService, never()).activate("com.crm.gone");
    }

    @Test
    void should_reactivate_already_registered_plugin_on_startup() {
        PluginManifest manifest = manifest("com.crm.known");
        PluginHost host = hostScanning(PluginDiscoveryResult.valid("/plugins/known.zip", manifest));
        when(registryService.findByPluginId("com.crm.known"))
            .thenReturn(Optional.of(registryRow("com.crm.known", PluginState.ACTIVE)));

        host.startPlugins();

        verify(registryService, never()).register(any());
        verify(registryService).activate("com.crm.known");
    }

    @Test
    void should_survive_marking_failure_when_registry_also_fails() {
        PluginManifest failing = manifest("com.crm.failing");
        PluginManifest healthy = manifest("com.crm.healthy");
        PluginHost host = hostScanning(
            PluginDiscoveryResult.valid("/plugins/failing.zip", failing),
            PluginDiscoveryResult.valid("/plugins/healthy.zip", healthy));
        when(registryService.findByPluginId("com.crm.failing")).thenReturn(Optional.empty());
        when(registryService.findByPluginId("com.crm.healthy")).thenReturn(Optional.empty());
        when(registryService.register(failing)).thenThrow(new IllegalStateException("boom"));
        when(registryService.markLoadFailed(eq("com.crm.failing"), any()))
            .thenThrow(new IllegalStateException("registry down"));

        host.startPlugins();

        verify(registryService).activate("com.crm.healthy");
    }
}
