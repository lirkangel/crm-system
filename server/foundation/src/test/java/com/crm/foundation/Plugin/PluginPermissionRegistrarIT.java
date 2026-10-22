package com.crm.foundation.Plugin;

import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plugin permissions (design 6.4): the manifest's {@code permissions-declared}
 * keys are inserted with the plugin's id, and only keys inserted by a
 * registration are granted to {@code admin} — an admin who removed a grant
 * doesn't get it back on the next start.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class PluginPermissionRegistrarIT {

    private static final String PLUGIN_ID = "com.crm.permdemo";
    private static final String READ = "permdemo.items.read";
    private static final String MANAGE = "permdemo.items.manage";

    @Autowired
    private PluginPermissionRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbc;

    /** Builds the plugin package declaring two permissions before the plugin host starts. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("permission-plugins-");
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .schema("plugin_permdemo")
            .entry("permdemo.PermDemoPlugin")
            .permissions(READ, MANAGE)
            .source("permdemo.PermDemoPlugin", """
                package permdemo;
                public class PermDemoPlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """)
            .buildTo(pluginDir.resolve("permdemo.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private int permissionRows(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM permissions WHERE key = ?", Integer.class, key);
    }

    private boolean adminHolds(String key) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE r.code = 'admin' AND p.key = ?
            """, Integer.class, key) == 1;
    }

    @Test
    void declared_permissions_inserted_with_plugin_id() {
        List<String> pluginIds = jdbc.queryForList(
            "SELECT plugin_id FROM permissions WHERE key IN (?, ?) ORDER BY key", String.class, READ, MANAGE);

        assertThat(pluginIds).containsExactly(PLUGIN_ID, PLUGIN_ID);
    }

    @Test
    void new_keys_granted_to_admin() {
        assertThat(adminHolds(READ)).isTrue();
        assertThat(adminHolds(MANAGE)).isTrue();
    }

    @Test
    void regrant_only_on_first_insert() {
        String key = "permdemo.regrant." + System.nanoTime();
        registrar.register(PLUGIN_ID, List.of(key));
        assertThat(adminHolds(key)).isTrue();

        jdbc.update("""
            DELETE FROM role_permissions
            WHERE role_id = (SELECT id FROM roles WHERE code = 'admin')
              AND permission_id = (SELECT id FROM permissions WHERE key = ?)
            """, key);
        registrar.register(PLUGIN_ID, List.of(key));

        assertThat(adminHolds(key)).isFalse();
        assertThat(permissionRows(key)).isEqualTo(1);
    }

    @Test
    void foundation_permissions_untouched() {
        int foundationBefore = jdbc.queryForObject(
            "SELECT count(*) FROM permissions WHERE plugin_id IS NULL", Integer.class);
        int grantsBefore = jdbc.queryForObject("SELECT count(*) FROM role_permissions", Integer.class);

        // A plugin re-declaring a Foundation key must neither claim it nor change its grants
        registrar.register(PLUGIN_ID, List.of("core.roles.read", READ));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM permissions WHERE plugin_id IS NULL", Integer.class)).isEqualTo(foundationBefore);
        assertThat(jdbc.queryForObject(
            "SELECT plugin_id FROM permissions WHERE key = 'core.roles.read'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions", Integer.class))
            .isEqualTo(grantsBefore);
    }
}
