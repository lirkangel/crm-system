package com.crm.foundation.Plugin;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses and validates {@code plugin.yaml} (kebab-case keys per the plugin
 * contract) into a {@link PluginManifest}. Required fields: {@code id},
 * {@code version}, {@code schema}, {@code entry}.
 */
@Component
public class PluginManifestParser {

    private static final Pattern ROUTE_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    private static final List<String> REQUIRED_FIELDS = List.of("id", "version", "schema", "entry");

    public PluginManifest parse(String yamlContent) {
        Map<String, Object> root = loadMapping(yamlContent);
        requireFields(root);
        String id = stringField(root, "id");
        return new PluginManifest(
            id,
            stringField(root, "version"),
            stringMap(root.get("display-name")),
            stringMap(root.get("depends-on")),
            stringList(root.get("permissions-declared")),
            stringList(root.get("foundation-services-used")),
            stringField(root, "schema"),
            stringField(root, "entry"),
            resolveRoute(root, id));
    }

    private static String resolveRoute(Map<String, Object> root, String id) {
        Object declared = root.get("route");
        String route = declared == null
            ? id.substring(id.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : String.valueOf(declared).trim();
        if (!ROUTE_PATTERN.matcher(route).matches()) {
            throw new InvalidPluginPackageException(
                "plugin.yaml route '" + route + "' is invalid: must match " + ROUTE_PATTERN.pattern()
                    + (declared == null ? " (derived from the last segment of id; set 'route' explicitly)" : ""));
        }
        return route;
    }

    private Map<String, Object> loadMapping(String yamlContent) {
        Object root;
        try {
            // SafeConstructor: manifests are untrusted input; no arbitrary type instantiation
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlContent);
        } catch (RuntimeException e) {
            throw new InvalidPluginPackageException("plugin.yaml is not valid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new InvalidPluginPackageException("plugin.yaml must be a YAML mapping of manifest fields");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private void requireFields(Map<String, Object> root) {
        List<String> missing = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            if (!(root.get(field) instanceof String value) || value.isBlank()) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new InvalidPluginPackageException(
                "plugin.yaml is missing required field(s): " + String.join(", ", missing));
        }
    }

    private static String stringField(Map<String, Object> root, String field) {
        return ((String) root.get(field)).trim();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, val) -> result.put(String.valueOf(key), String.valueOf(val)));
        return Map.copyOf(result);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
