package com.crm.foundation.hotel;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** A slice-1 database (V1 with data) upgrades to V2 using the db/ scripts of the packaged hotel ZIP. */
class HotelUpgradeIT extends HotelITSupport {

    private static final String SCRATCH_SCHEMA = "hotel_upgrade_scratch";

    @Autowired
    private DataSource dataSource;

    @Test
    void slice1_data_migrates_to_v2() throws IOException {
        Path scripts = extractDbScripts();
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCRATCH_SCHEMA + " CASCADE");
        try {
            Flyway v1 = flyway(scripts).target("1").load();
            v1.migrate();
            assertThat(v1.info().current().getVersion().getVersion()).isEqualTo("1");

            UUID type = UUID.randomUUID();
            UUID room = UUID.randomUUID();
            UUID guest = UUID.randomUUID();
            UUID confirmed = UUID.randomUUID();
            UUID cancelled = UUID.randomUUID();
            jdbc.update("INSERT INTO " + SCRATCH_SCHEMA + ".room_type (id, code, name_i18n, max_guests, nightly_rate_gross_vnd) "
                + "VALUES (?, 'UP', '{\"en\":\"Upgrade\"}'::jsonb, 2, 1000000)", type);
            jdbc.update("INSERT INTO " + SCRATCH_SCHEMA + ".room (id, number, room_type_id) VALUES (?, 'U1', ?)", room, type);
            jdbc.update("INSERT INTO " + SCRATCH_SCHEMA + ".guest (id, full_name, phone, search_key) "
                + "VALUES (?, 'Up Grade', '0900000001', 'up grade 0900000001')", guest);
            insertReservation(confirmed, "UP-1", guest, room, "2030-01-10", "2030-01-12", "CONFIRMED");
            insertReservation(cancelled, "UP-2", guest, room, "2030-01-10", "2030-01-12", "CANCELLED");

            Flyway v2 = flyway(scripts).load();
            v2.migrate();

            assertThat(v2.info().current().getVersion().getVersion()).isEqualTo("2");
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status, source, ota_channel, ota_reference, "
                + "checked_in_at, checked_out_at FROM " + SCRATCH_SCHEMA + ".reservation ORDER BY code");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("id", confirmed).containsEntry("status", "CONFIRMED")
                .containsEntry("source", "WALK_IN").containsEntry("ota_channel", null)
                .containsEntry("checked_in_at", null);
            assertThat(rows.get(1)).containsEntry("id", cancelled).containsEntry("status", "CANCELLED")
                .containsEntry("source", "WALK_IN");
            Integer newTables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name IN ('stay_guest', 'folio_charge', 'folio_payment')", Integer.class, SCRATCH_SCHEMA);
            assertThat(newTables).isEqualTo(3);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCRATCH_SCHEMA + " CASCADE");
        }
    }

    private void insertReservation(UUID id, String code, UUID guest, UUID room, String in, String out, String status) {
        jdbc.update("INSERT INTO " + SCRATCH_SCHEMA + ".reservation (id, code, guest_id, room_id, check_in, check_out, adults, "
            + "status, nightly_rate_gross_vnd, total_gross_vnd) VALUES (?, ?, ?, ?, ?::date, ?::date, 1, ?, 1000000, 2000000)",
            id, code, guest, room, in, out, status);
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(Path scripts) {
        return Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema(SCRATCH_SCHEMA)
            .schemas(SCRATCH_SCHEMA)
            .createSchemas(true)
            .locations("filesystem:" + scripts);
    }

    /** The {@code db/*.sql} scripts of the hotel ZIP the application under test installed. */
    private static Path extractDbScripts() throws IOException {
        Path zip = hotelPluginDirectory().resolve("hotel-plugin.zip");
        Path dir = Files.createTempDirectory("hotel-upgrade-db-");
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("db/") && entry.getName().endsWith(".sql")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, dir.resolve(entry.getName().substring("db/".length())));
                    }
                }
            }
        }
        return dir;
    }
}
