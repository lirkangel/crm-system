package com.crm.foundation.hotel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The retention purge: scans older than 30 days go, younger ones stay; identity records are kept. */
class IdScanPurgeIT extends HotelITSupport {

    private static final Instant NEVER = Instant.EPOCH;

    @Test
    void purges_older_than_30_days_keeps_younger() {
        UUID reservation = reservation();
        Instant now = Instant.now();
        UUID old = seedScan(reservation, now.minus(30, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES));
        UUID young = seedScan(reservation, now.minus(29, ChronoUnit.DAYS));

        int purged = purgeScansOlderThan(now.minus(30, ChronoUnit.DAYS));

        assertThat(purged).isEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(young)).isTrue();
    }

    @Test
    void purged_photo_404_identity_kept() {
        String token = adminToken();
        UUID reservation = reservation();
        UUID stayGuest = checkedInGuest(reservation);
        UUID scan = seedScan(reservation, Instant.now().minus(31, ChronoUnit.DAYS));
        jdbc.update("UPDATE hotel.id_scan SET stay_guest_id = ? WHERE id = ?", stayGuest, scan);
        String path = HOTEL_API + "/reservations/" + reservation + "/stay-guests/" + stayGuest + "/id-scan";
        assertThat(get(path, token).getStatusCode().value()).isEqualTo(200);

        purgeScansOlderThan(Instant.now().minus(30, ChronoUnit.DAYS));

        assertThat(get(path, token).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
            "SELECT id_number FROM hotel.stay_guest WHERE id = ?", String.class, stayGuest)).isEqualTo("079090300001");
    }

    @Test
    void purge_audit_has_count_only() {
        UUID reservation = reservation();
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        seedScan(reservation, old);
        seedScan(reservation, old);
        long before = purgeAudits();

        int purged = purgeScansOlderThan(Instant.now().minus(30, ChronoUnit.DAYS));

        assertThat(purged).isGreaterThanOrEqualTo(2);
        assertThat(purgeAudits()).isEqualTo(before + 1);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT entity_type, entity_id, user_id, source_ip, plugin_id, after_json::text AS after
            FROM audit_event WHERE op = 'ID_SCAN_PURGE' ORDER BY occurred_at DESC LIMIT 1
            """);
        assertThat(row.get("entity_type")).isEqualTo("IdScan");
        assertThat(row.get("entity_id")).isNull();
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("source_ip")).isNull();
        assertThat(row.get("plugin_id")).isEqualTo(HOTEL_PLUGIN_ID);
        assertThat(row.get("after")).isEqualTo("{\"count\": " + purged + "}");
    }

    @Test
    void no_audit_when_nothing_purged() {
        UUID reservation = reservation();
        seedScan(reservation, Instant.now().minus(1, ChronoUnit.DAYS));
        long before = purgeAudits();

        int purged = purgeScansOlderThan(NEVER);

        assertThat(purged).isZero();
        assertThat(purgeAudits()).isEqualTo(before);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private long purgeAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE op = 'ID_SCAN_PURGE'", Long.class);
    }

    private boolean exists(UUID scan) {
        return jdbc.queryForObject("SELECT count(*) FROM hotel.id_scan WHERE id = ?", Integer.class, scan) > 0;
    }

    private UUID reservation() {
        String token = adminToken();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        UUID room = newRoom(token, "PG" + tag, newRoomType(token, "PG" + tag, 3, 900_000, true), true);
        LocalDate today = hotelToday();
        return book(token, room, today, today.plusDays(1), 1);
    }

    private UUID checkedInGuest(UUID reservation) {
        org.springframework.http.ResponseEntity<String> response = post(
            HOTEL_API + "/reservations/" + reservation + "/check-in", adminToken(),
            Map.of("adults", List.of(adult("CCCD", "079090300001", "Purge Guest"))));
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return jdbc.queryForObject(
            "SELECT id FROM hotel.stay_guest WHERE reservation_id = ?", UUID.class, reservation);
    }

    /** A stored scan inserted with SQL at the given upload time (a distinct sha256 each). */
    private UUID seedScan(UUID reservation, Instant uploadedAt) {
        UUID id = UUID.randomUUID();
        byte[] image = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        byte[] sha = new byte[32];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(sha);
        jdbc.update("""
            INSERT INTO hotel.id_scan (id, reservation_id, content_type, image, sha256, size_bytes, uploaded_at, version)
            VALUES (?, ?, 'image/jpeg', ?, ?, ?, ?, 0)
            """, id, reservation, image, sha, image.length, java.sql.Timestamp.from(uploadedAt));
        return id;
    }
}
