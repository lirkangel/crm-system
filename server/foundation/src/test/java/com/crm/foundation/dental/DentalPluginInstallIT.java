package com.crm.foundation.dental;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Plugin.PluginState;
import com.crm.foundation.Service.PluginRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The real packaged dental ZIP installs with the public route off (the default) and no field key. */
class DentalPluginInstallIT extends DentalITSupport {

    private static final List<String> DENTAL_PERMISSIONS = List.of(
        "dental.appointments.read",
        "dental.appointments.write",
        "dental.patients.read",
        "dental.patients.write",
        "dental.previsit.read");

    @Autowired
    private PluginRegistryService registryService;

    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> dentalPluginDirectory().toString());
    }

    @Test
    void dental_plugin_active_with_schema_and_permissions() {
        PluginRegistry entry = registryService.findByPluginId(DENTAL_PLUGIN_ID).orElseThrow();
        assertThat(entry.getState()).as(entry.getErrorMessage()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(entry.getSchemaName()).isEqualTo(DENTAL_SCHEMA);
        assertThat(entry.getVersion()).isEqualTo("0.1.0");
        assertThat(registryService.findByPluginId(HOTEL_PLUGIN_ID).orElseThrow().getState())
            .isEqualTo(PluginState.ACTIVE.name());

        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
            String.class, DENTAL_SCHEMA);
        assertThat(tables).contains("patient", "appointment", "visit_link", "id_scan", "previsit_form");

        List<String> granted = jdbc.queryForList("""
            SELECT p.key FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE r.code = 'admin' AND p.plugin_id = ?
            ORDER BY p.key
            """, String.class, DENTAL_PLUGIN_ID);
        assertThat(granted).isEqualTo(DENTAL_PERMISSIONS);
    }

    @Test
    void appointment_status_is_constrained() {
        Integer constraints = jdbc.queryForObject("""
            SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE c.conname = 'appointment_status_valid' AND c.contype = 'c' AND n.nspname = ?
            """, Integer.class, DENTAL_SCHEMA);
        assertThat(constraints).isEqualTo(1);
    }

    @Test
    void dental_zip_lib_holds_dental_cccd_scan_and_zxing_only() throws Exception {
        List<String> jars = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
            dentalPluginDirectory().resolve("dental-plugin.zip").toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                .filter(name -> name.startsWith("lib/") && !name.equals("lib/"))
                .forEach(jars::add);
        }

        assertThat(jars).hasSize(4);
        assertThat(jars).anyMatch(name -> name.matches("lib/dental-.+\\.jar"));
        assertThat(jars).anyMatch(name -> name.matches("lib/cccd-scan-.+\\.jar"));
        assertThat(jars).anyMatch(name -> name.matches("lib/zxing-core-.+\\.jar"));
        assertThat(jars).anyMatch(name -> name.matches("lib/zxing-javase-.+\\.jar"));
    }
}
