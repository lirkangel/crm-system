package com.crm.foundation.hotel;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Plugin.PluginState;
import com.crm.foundation.Service.PluginRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The real packaged hotel ZIP installs: registry, schema, permissions and the no-overlap guarantee. */
class HotelPluginInstallIT extends HotelITSupport {

    private static final List<String> HOTEL_PERMISSIONS = List.of(
        "hotel.guests.manage",
        "hotel.guests.read",
        "hotel.reservations.cancel",
        "hotel.reservations.create",
        "hotel.reservations.read",
        "hotel.rooms.manage",
        "hotel.rooms.read");

    @Autowired
    private PluginRegistryService registryService;

    @Test
    void hotel_plugin_is_active() {
        PluginRegistry entry = registryService.findByPluginId(HOTEL_PLUGIN_ID).orElseThrow();

        assertThat(entry.getState()).as(entry.getErrorMessage()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(entry.getSchemaName()).isEqualTo(HOTEL_SCHEMA);
        assertThat(entry.getVersion()).isEqualTo("0.1.0");
    }

    @Test
    void hotel_tables_exist() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name IN "
                + "('room_type', 'room', 'guest', 'reservation') ORDER BY table_name",
            String.class, HOTEL_SCHEMA);

        assertThat(tables).containsExactly("guest", "reservation", "room", "room_type");
    }

    @Test
    void hotel_permissions_granted_to_admin() {
        List<String> granted = jdbc.queryForList("""
            SELECT p.key FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE r.code = 'admin' AND p.plugin_id = ?
            ORDER BY p.key
            """, String.class, HOTEL_PLUGIN_ID);

        assertThat(granted).isEqualTo(HOTEL_PERMISSIONS);
    }

    @Test
    void overlap_constraint_exists() {
        Integer constraints = jdbc.queryForObject("""
            SELECT count(*) FROM pg_constraint c
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE c.conname = 'reservation_no_overlap' AND c.contype = 'x' AND n.nspname = ?
            """, Integer.class, HOTEL_SCHEMA);

        assertThat(constraints).isEqualTo(1);
    }

    @Test
    void admin_token_carries_hotel_permissions() throws Exception {
        // Permissions reach a user through the JWT at login; admin logs in after the plugin installed
        String payload = adminToken().split("\\.")[1];
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(payload));

        List<String> perms = new ArrayList<>();
        claims.path("perms").forEach(perm -> perms.add(perm.asText()));
        assertThat(perms).containsAll(HOTEL_PERMISSIONS);
    }
}
