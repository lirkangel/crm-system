package com.crm.foundation.Plugin;

import java.util.List;
import java.util.Map;

/**
 * Parsed {@code plugin.yaml} (spec: locked plugin contract). Serialized as-is
 * into {@code plugin_registry.manifest_json}. YAML parsing/validation is T014;
 * this is the in-memory model.
 *
 * @param id                      reverse-DNS plugin id, e.g. {@code com.crm.demo}
 * @param version                 semver of the plugin package
 * @param displayName             locale → human-readable name, e.g. {@code {vi: "...", en: "..."}}
 * @param dependsOn               dependency → semver range, e.g. {@code {foundation: ">=0.1.0,<2.0.0"}}
 * @param permissionsDeclared     permission keys the plugin contributes
 * @param foundationServicesUsed  foundation services the plugin consumes (persistence, rest, …)
 * @param schema                  dedicated Postgres schema name
 * @param entry                   fully-qualified entrypoint class
 */
public record PluginManifest(
    String id,
    String version,
    Map<String, String> displayName,
    Map<String, String> dependsOn,
    List<String> permissionsDeclared,
    List<String> foundationServicesUsed,
    String schema,
    String entry
) {
}
