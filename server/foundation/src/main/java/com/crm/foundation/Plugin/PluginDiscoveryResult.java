package com.crm.foundation.Plugin;

/**
 * Outcome of validating one plugin package during directory scan: either a
 * parsed manifest, or a rejection reason. A rejected package never aborts the
 * scan — other plugins still load (T014a).
 *
 * @param packagePath     absolute path of the scanned ZIP
 * @param manifest        parsed manifest; {@code null} when rejected
 * @param rejectionReason why the package was rejected; {@code null} when valid
 */
public record PluginDiscoveryResult(String packagePath, PluginManifest manifest, String rejectionReason) {

    public static PluginDiscoveryResult valid(String packagePath, PluginManifest manifest) {
        return new PluginDiscoveryResult(packagePath, manifest, null);
    }

    public static PluginDiscoveryResult rejected(String packagePath, String reason) {
        return new PluginDiscoveryResult(packagePath, null, reason);
    }

    public boolean isValid() {
        return manifest != null;
    }
}
