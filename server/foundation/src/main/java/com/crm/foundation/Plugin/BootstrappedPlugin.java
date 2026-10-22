package com.crm.foundation.Plugin;

import java.util.List;

/**
 * Outcome of the early (pre-{@code EntityManagerFactory}) plugin phase for one
 * valid package: its classloader and the {@code @Entity} classes it
 * contributes to Foundation's persistence unit.
 *
 * @param packagePath      absolute path of the plugin ZIP
 * @param manifest         parsed manifest
 * @param classLoader      the plugin's {@link ParentLastClassLoader}; {@code null} unless {@code READY}
 * @param entityClassNames {@code @Entity} classes under the entry's package; empty unless {@code READY}
 * @param status           {@code READY} to contribute, {@code FAILED} on any error, {@code SKIPPED}
 *                         when the registry says {@code DISABLED}/{@code UNINSTALL_PENDING}
 * @param failureReason    why the plugin failed; {@code null} otherwise
 */
public record BootstrappedPlugin(
    String packagePath,
    PluginManifest manifest,
    ClassLoader classLoader,
    List<String> entityClassNames,
    Status status,
    String failureReason
) {

    public enum Status { READY, FAILED, SKIPPED }

    static BootstrappedPlugin ready(
            String packagePath, PluginManifest manifest, ClassLoader classLoader, List<String> entityClassNames) {
        return new BootstrappedPlugin(
            packagePath, manifest, classLoader, List.copyOf(entityClassNames), Status.READY, null);
    }

    static BootstrappedPlugin failed(String packagePath, PluginManifest manifest, String reason) {
        return new BootstrappedPlugin(packagePath, manifest, null, List.of(), Status.FAILED, reason);
    }

    static BootstrappedPlugin skipped(String packagePath, PluginManifest manifest) {
        return new BootstrappedPlugin(packagePath, manifest, null, List.of(), Status.SKIPPED, null);
    }
}
