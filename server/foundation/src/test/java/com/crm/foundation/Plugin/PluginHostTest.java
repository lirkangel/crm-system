package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Service.PluginRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginHostTest {

    @Mock
    private PluginRegistryService registryService;

    private final GenericApplicationContext parent = new GenericApplicationContext();

    /* Subclass doubles — Mockito's inline mock-maker can't instrument concrete classes on this JDK. */
    private final List<String> loadedEntrypoints = new ArrayList<>();
    private final List<String> failEntrypointFor = new ArrayList<>();
    private final List<String> failStartFor = new ArrayList<>();
    private final List<String> failRoutesFor = new ArrayList<>();
    private final Map<String, ConfigurableApplicationContext> startedContexts = new HashMap<>();
    private final List<ApplicationContext> parentsSeen = new ArrayList<>();
    /** route → context, as the route registry holds them. */
    private final Map<String, ApplicationContext> registeredRoutes = new HashMap<>();
    private final List<String> unregisteredRoutes = new ArrayList<>();

    private static PluginManifest manifest(String id, String route) {
        return new PluginManifest(
            id, "1.0.0", Map.of(), Map.of(), List.of(), List.of(), "demo", id + ".Entry", route);
    }

    private static PluginManifest manifest(String id) {
        return manifest(id, id.substring(id.lastIndexOf('.') + 1));
    }

    private BootstrappedPlugin ready(PluginManifest manifest) {
        return BootstrappedPlugin.ready("/plugins/" + manifest.id() + ".zip", manifest, getClass().getClassLoader(), List.of());
    }

    private static PluginRegistry registryRow(String pluginId, PluginState state) {
        PluginRegistry row = new PluginRegistry();
        row.setPluginId(pluginId);
        row.setState(state.name());
        return row;
    }

    private PluginHost hostWith(BootstrappedPlugin... plugins) {
        PluginBootstrap bootstrap = new PluginBootstrap(null, null, null, null, null, null, null, null) {
            @Override
            public synchronized PluginBootstrapReport report() {
                return new PluginBootstrapReport(List.of(plugins));
            }
        };
        PluginLoader loader = new PluginLoader() {
            @Override
            public PluginActivator instantiateEntry(ClassLoader classLoader, PluginManifest manifest) {
                if (failEntrypointFor.contains(manifest.id())) {
                    throw new InvalidPluginPackageException("entry class missing for " + manifest.id());
                }
                return () -> loadedEntrypoints.add(manifest.id());
            }
        };
        PluginContextFactory contexts = new PluginContextFactory(null, null, null) {
            @Override
            public ConfigurableApplicationContext start(BootstrappedPlugin plugin, ApplicationContext parentContext) {
                parentsSeen.add(parentContext);
                if (failStartFor.contains(plugin.manifest().id())) {
                    throw new IllegalStateException("context refresh failed for " + plugin.manifest().id());
                }
                GenericApplicationContext child = new GenericApplicationContext();
                child.refresh();
                startedContexts.put(plugin.manifest().id(), child);
                return child;
            }
        };
        PluginRouteRegistry routes = new PluginRouteRegistry() {
            @Override
            public synchronized void register(String route, ApplicationContext pluginContext) {
                if (failRoutesFor.contains(route) || registeredRoutes.containsKey(route)) {
                    throw new InvalidPluginPackageException("route '" + route + "' is already registered");
                }
                registeredRoutes.put(route, pluginContext);
            }

            @Override
            public synchronized void unregister(String route) {
                unregisteredRoutes.add(route);
                registeredRoutes.remove(route);
            }
        };
        return new PluginHost(bootstrap, registryService, loader, contexts, routes, parent);
    }

    @Test
    void should_register_start_route_and_activate_new_ready_plugin() {
        PluginManifest manifest = manifest("com.crm.demo");
        PluginHost host = hostWith(ready(manifest));
        when(registryService.findByPluginId("com.crm.demo")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).register(manifest);
        verify(registryService).activate("com.crm.demo");
        assertThat(parentsSeen).containsExactly(parent);
        assertThat(loadedEntrypoints).containsExactly("com.crm.demo");
        assertThat(registeredRoutes).containsEntry("demo", startedContexts.get("com.crm.demo"));
        assertThat(startedContexts.get("com.crm.demo").isActive()).isTrue();
    }

    @Test
    void should_reactivate_already_registered_plugin_on_startup() {
        PluginManifest manifest = manifest("com.crm.known");
        PluginHost host = hostWith(ready(manifest));
        when(registryService.findByPluginId("com.crm.known"))
            .thenReturn(Optional.of(registryRow("com.crm.known", PluginState.ACTIVE)));

        host.startPlugins();

        verify(registryService, never()).register(any());
        verify(registryService).activate("com.crm.known");
    }

    @Test
    void should_register_and_mark_load_failed_when_early_phase_failed() {
        PluginManifest manifest = manifest("com.crm.badmigration");
        PluginHost host = hostWith(BootstrappedPlugin.failed("/plugins/bad.zip", manifest, "migration failed: syntax"));
        when(registryService.findByPluginId("com.crm.badmigration")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).register(manifest);
        verify(registryService).markLoadFailed("com.crm.badmigration", "migration failed: syntax");
        verify(registryService, never()).activate(anyString());
        assertThat(parentsSeen).isEmpty();
    }

    @Test
    void should_not_start_skipped_plugin() {
        PluginManifest manifest = manifest("com.crm.disabled");
        PluginHost host = hostWith(BootstrappedPlugin.skipped("/plugins/disabled.zip", manifest));
        when(registryService.findByPluginId("com.crm.disabled"))
            .thenReturn(Optional.of(registryRow("com.crm.disabled", PluginState.DISABLED)));

        host.startPlugins();

        verify(registryService, never()).register(any());
        verify(registryService, never()).activate(anyString());
        verify(registryService, never()).markLoadFailed(anyString(), any());
        assertThat(parentsSeen).isEmpty();
        assertThat(registeredRoutes).isEmpty();
    }

    @Test
    void should_mark_load_failed_when_child_context_fails_to_start() {
        PluginManifest manifest = manifest("com.crm.badcontext");
        failStartFor.add("com.crm.badcontext");
        PluginHost host = hostWith(ready(manifest));
        when(registryService.findByPluginId("com.crm.badcontext")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).markLoadFailed(eq("com.crm.badcontext"), contains("context refresh failed"));
        verify(registryService, never()).activate(anyString());
        assertThat(loadedEntrypoints).isEmpty();
        assertThat(registeredRoutes).isEmpty();
    }

    @Test
    void should_close_child_and_mark_load_failed_when_entrypoint_fails() {
        PluginManifest manifest = manifest("com.crm.broken");
        failEntrypointFor.add("com.crm.broken");
        PluginHost host = hostWith(ready(manifest));
        when(registryService.findByPluginId("com.crm.broken")).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).markLoadFailed(eq("com.crm.broken"), contains("entry class missing"));
        verify(registryService, never()).activate(anyString());
        assertThat(startedContexts.get("com.crm.broken").isActive()).isFalse();
        assertThat(registeredRoutes).isEmpty();
    }

    @Test
    void should_fail_second_plugin_on_duplicate_route_without_touching_the_first() {
        PluginManifest first = manifest("com.crm.first", "shared");
        PluginManifest second = manifest("com.crm.second", "shared");
        PluginHost host = hostWith(ready(first), ready(second));
        when(registryService.findByPluginId(anyString())).thenReturn(Optional.empty());

        host.startPlugins();

        verify(registryService).activate("com.crm.first");
        verify(registryService).markLoadFailed(eq("com.crm.second"), contains("already registered"));
        verify(registryService, never()).activate("com.crm.second");
        assertThat(registeredRoutes).containsEntry("shared", startedContexts.get("com.crm.first"));
        assertThat(unregisteredRoutes).isEmpty();
        assertThat(startedContexts.get("com.crm.second").isActive()).isFalse();
    }

    @Test
    void should_unregister_routes_and_close_child_when_activation_fails() {
        PluginManifest manifest = manifest("com.crm.late");
        PluginHost host = hostWith(ready(manifest));
        when(registryService.findByPluginId("com.crm.late")).thenReturn(Optional.empty());
        when(registryService.activate("com.crm.late")).thenThrow(new IllegalStateException("registry write failed"));

        host.startPlugins();

        verify(registryService).markLoadFailed(eq("com.crm.late"), contains("registry write failed"));
        assertThat(unregisteredRoutes).containsExactly("late");
        assertThat(registeredRoutes).isEmpty();
        assertThat(startedContexts.get("com.crm.late").isActive()).isFalse();
    }

    @Test
    void should_mark_load_failed_and_continue_when_registration_throws() {
        PluginManifest failing = manifest("com.crm.failing");
        PluginManifest healthy = manifest("com.crm.healthy");
        PluginHost host = hostWith(ready(failing), ready(healthy));
        when(registryService.findByPluginId(anyString())).thenReturn(Optional.empty());
        when(registryService.register(failing)).thenThrow(new IllegalStateException("schema collision"));

        host.startPlugins();

        verify(registryService).markLoadFailed(eq("com.crm.failing"), contains("schema collision"));
        verify(registryService, never()).activate("com.crm.failing");
        verify(registryService).activate("com.crm.healthy");
        assertThat(loadedEntrypoints).containsExactly("com.crm.healthy");
    }

    @Test
    void should_survive_marking_failure_when_registry_also_fails() {
        PluginManifest failing = manifest("com.crm.failing");
        PluginManifest healthy = manifest("com.crm.healthy");
        failStartFor.add("com.crm.failing");
        PluginHost host = hostWith(ready(failing), ready(healthy));
        when(registryService.findByPluginId(anyString())).thenReturn(Optional.empty());
        when(registryService.markLoadFailed(eq("com.crm.failing"), any()))
            .thenThrow(new IllegalStateException("registry down"));

        host.startPlugins();

        verify(registryService).activate("com.crm.healthy");
    }

    @Test
    void should_stop_running_plugins_on_destroy() {
        PluginManifest manifest = manifest("com.crm.demo");
        PluginHost host = hostWith(ready(manifest));
        when(registryService.findByPluginId("com.crm.demo")).thenReturn(Optional.empty());
        host.startPlugins();

        host.destroy();

        assertThat(unregisteredRoutes).containsExactly("demo");
        assertThat(startedContexts.get("com.crm.demo").isActive()).isFalse();
    }
}
