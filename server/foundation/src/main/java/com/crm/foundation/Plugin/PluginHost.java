package com.crm.foundation.Plugin;

import com.crm.foundation.Service.PluginRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Late plugin phase (design 6.1), run once the application context is up.
 * Works through {@link PluginBootstrap#report()} — discovery, classloaders,
 * migrations and entity mapping already happened in the early phase — and per
 * plugin: registers it in {@code plugin_registry} if new, records early-phase
 * failures as {@code LOAD_FAILED}, and starts every READY plugin: its Spring
 * child context ({@link PluginContextFactory}), its entry's
 * {@link PluginActivator#onLoad()}, and its routes ({@link PluginRouteRegistry}) and its
 * permissions ({@link PluginPermissionRegistrar}) — then marks it {@code ACTIVE}.
 *
 * <p>Strictly per plugin (T014a): a plugin that fails to start is torn down
 * (child context closed, routes removed) and marked {@code LOAD_FAILED}, and
 * the application and the other plugins carry on.
 */
@Component
public class PluginHost implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PluginHost.class);

    private final PluginBootstrap bootstrap;
    private final PluginRegistryService registryService;
    private final PluginLoader pluginLoader;
    private final PluginContextFactory contextFactory;
    private final PluginRouteRegistry routes;
    private final PluginPermissionRegistrar permissions;
    private final ApplicationContext applicationContext;

    /** Running plugins: plugin id → child context, in start order. */
    private final Map<String, RunningPlugin> running = new LinkedHashMap<>();

    public PluginHost(
            PluginBootstrap bootstrap,
            PluginRegistryService registryService,
            PluginLoader pluginLoader,
            PluginContextFactory contextFactory,
            PluginRouteRegistry routes,
            PluginPermissionRegistrar permissions,
            ApplicationContext applicationContext) {
        this.bootstrap = bootstrap;
        this.registryService = registryService;
        this.pluginLoader = pluginLoader;
        this.contextFactory = contextFactory;
        this.routes = routes;
        this.permissions = permissions;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        startPlugins();
    }

    public void startPlugins() {
        for (BootstrappedPlugin plugin : bootstrap.report().plugins()) {
            startIsolated(plugin);
        }
    }

    private void startIsolated(BootstrappedPlugin plugin) {
        PluginManifest manifest = plugin.manifest();
        try {
            if (registryService.findByPluginId(manifest.id()).isEmpty()) {
                if (plugin.schemaConflict()) {
                    // Its schema is another plugin's and plugin_registry.schema_name is unique: no row is
                    // possible, and PluginBootstrap already logged why it failed
                    log.warn("Plugin {} left unregistered: its schema belongs to another plugin", manifest.id());
                    return;
                }
                registryService.register(manifest);
            }
        } catch (Exception e) {
            log.error("Plugin {} could not be registered: {}", manifest.id(), reason(e), e);
            markLoadFailedQuietly(manifest.id(), reason(e));
            return;
        }
        switch (plugin.status()) {
            case SKIPPED -> log.info("Skipping plugin {} (disabled or uninstall pending)", manifest.id());
            case FAILED -> {
                log.warn("Plugin {} failed in the early phase: {}", manifest.id(), plugin.failureReason());
                markLoadFailedQuietly(manifest.id(), plugin.failureReason());
            }
            case READY -> start(plugin);
        }
    }

    private void start(BootstrappedPlugin plugin) {
        PluginManifest manifest = plugin.manifest();
        ConfigurableApplicationContext child = null;
        boolean routesRegistered = false;
        try {
            child = contextFactory.start(plugin, applicationContext);
            pluginLoader.instantiateEntry(plugin.classLoader(), manifest).onLoad();
            routes.register(manifest.route(), child);
            routesRegistered = true;
            permissions.register(manifest.id(), manifest.permissionsDeclared());
            registryService.activate(manifest.id());
            running.put(manifest.id(), new RunningPlugin(manifest.route(), child));
            log.info("Plugin {} v{} active under {}{}",
                manifest.id(), manifest.version(), PluginRouteRegistry.PREFIX, manifest.route());
        } catch (Exception | LinkageError e) {
            log.error("Plugin {} failed to start: {}", manifest.id(), reason(e), e);
            if (routesRegistered) {
                // Only this plugin's own routes: a duplicate route belongs to the plugin that holds it
                unregisterQuietly(manifest.route());
            }
            closeQuietly(manifest.id(), child);
            markLoadFailedQuietly(manifest.id(), reason(e));
        }
    }

    /** The child context of a running plugin; for tests and diagnostics. */
    Optional<ConfigurableApplicationContext> runningContext(String pluginId) {
        RunningPlugin plugin = running.get(pluginId);
        return plugin == null ? Optional.empty() : Optional.of(plugin.context());
    }

    /** Stops running plugins (reverse start order) before the parent context goes away. */
    @Override
    public void destroy() {
        List<Map.Entry<String, RunningPlugin>> plugins = new ArrayList<>(running.entrySet());
        running.clear();
        for (int i = plugins.size() - 1; i >= 0; i--) {
            Map.Entry<String, RunningPlugin> plugin = plugins.get(i);
            unregisterQuietly(plugin.getValue().route());
            closeQuietly(plugin.getKey(), plugin.getValue().context());
        }
    }

    private static String reason(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    private void unregisterQuietly(String route) {
        try {
            routes.unregister(route);
        } catch (RuntimeException e) {
            log.error("Could not unregister plugin route {}: {}", route, e.getMessage(), e);
        }
    }

    private static void closeQuietly(String pluginId, ConfigurableApplicationContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (RuntimeException e) {
            log.error("Could not close the context of plugin {}: {}", pluginId, e.getMessage(), e);
        }
    }

    private void markLoadFailedQuietly(String pluginId, String reason) {
        try {
            registryService.markLoadFailed(pluginId, reason);
        } catch (Exception e) {
            // Startup resilience trumps bookkeeping: never let failure-marking abort the host
            log.error("Could not mark plugin {} as LOAD_FAILED: {}", pluginId, e.getMessage(), e);
        }
    }

    private record RunningPlugin(String route, ConfigurableApplicationContext context) {
    }
}
