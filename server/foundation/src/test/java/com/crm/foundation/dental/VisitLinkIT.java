package com.crm.foundation.dental;

import com.crm.foundation.hotel.CccdQrImages;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pre-visit links, CCCD photo storage and the 30-day purge, with the public route switched on. */
class VisitLinkIT extends VisitLinkITSupport {

    private static final String BASE = "https://nhakhoa.mathisterben.org/#d/";
    /** A throw-away test key: base64 of 32 random bytes. */
    private static final String TEST_KEY = "nERRVND9i0MldQ9zNhuM13SCQNs6+GfEj5DYcyrJiRw=";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> dentalPluginDirectory().toString());
        registry.add("dental.public.enabled", () -> "true");
        registry.add("dental.field-key", () -> TEST_KEY);
    }

    @Test
    void create_regenerate_revoke() {
        String staff = adminToken();
        UUID id = idOf(book(staff, futureDay()));
        assertThat(data(get(linkPath(id), staff)).path("exists").asBoolean()).isFalse();

        ResponseEntity<String> created = post(linkPath(id), staff, null);

        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        assertThat(created.getHeaders().getCacheControl()).contains("no-store");
        String first = tokenOf(created);
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(storedHash(id)).isEqualTo(sha256(first));
        assertThat(resolveStatus(first)).isEqualTo(200);
        JsonNode view = data(get(linkPath(id), staff));
        assertThat(view.path("exists").asBoolean()).isTrue();
        assertThat(view.path("createdAt").asText()).isNotBlank();
        assertThat(view.has("url") || view.has("token")).as("the status never carries the token").isFalse();

        String second = tokenOf(post(linkPath(id), staff, null));

        assertThat(second).isNotEqualTo(first);
        assertThat(storedHash(id)).isEqualTo(sha256(second));
        assertThat(count("visit_link", "appointment_id", id)).isEqualTo(1);
        assertThat(resolveStatus(first)).as("the replaced token is dead").isEqualTo(404);
        assertThat(resolveStatus(second)).isEqualTo(200);

        assertThat(exchange(HttpMethod.DELETE, linkPath(id), staff, null).getStatusCode().value()).isEqualTo(204);
        assertThat(resolveStatus(second)).isEqualTo(404);
        assertThat(data(get(linkPath(id), staff)).path("exists").asBoolean()).isFalse();
        assertThat(exchange(HttpMethod.DELETE, linkPath(id), staff, null).getStatusCode().value()).isEqualTo(404);
        assertThat(post(linkPath(UUID.randomUUID()), staff, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void resolve_rejects_bad_headers() {
        String staff = adminToken();
        UUID id = idOf(book(staff, futureDay()));
        String token = tokenOf(post(linkPath(id), staff, null));

        assertThat(resolveStatus(token)).isEqualTo(200);
        assertThat(resolveStatus(null)).isEqualTo(404);
        assertThat(resolveStatus("")).isEqualTo(404);
        assertThat(resolveStatus("   ")).isEqualTo(404);
        assertThat(resolveStatus("x".repeat(101))).isEqualTo(404);
        assertThat(resolveStatus(token + "A")).isEqualTo(404);
    }

    @Test
    void expires_2359_on_appointment_date() {
        String staff = adminToken();
        LocalDate day = futureDay();
        UUID id = idOf(book(staff, day));

        ResponseEntity<String> created = post(linkPath(id), staff, null);

        Instant expected = day.atTime(23, 59).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        assertThat(Instant.parse(data(created).path("expiresAt").asText())).isEqualTo(expected);
        assertThat(expiresAt(id)).isEqualTo(expected);
        assertThat(Instant.parse(data(get(linkPath(id), staff)).path("expiresAt").asText())).isEqualTo(expected);
    }

    @Test
    void past_visit_day_cannot_get_link_409() {
        String staff = adminToken();
        UUID id = idOf(book(staff, futureDay()));
        jdbc.update("UPDATE dental.appointment SET starts_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)), id);

        ResponseEntity<String> response = post(linkPath(id), staff, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("DENTAL_VISIT_CLOSED");
        assertThat(count("visit_link", "appointment_id", id)).isZero();
    }

    @Test
    void only_booked_gets_link() {
        String staff = adminToken();
        for (String status : List.of("ARRIVED", "CANCELLED", "NO_SHOW")) {
            UUID id = idOf(book(staff, futureDay()));
            jdbc.update("UPDATE dental.appointment SET status = ? WHERE id = ?", status, id);

            ResponseEntity<String> response = post(linkPath(id), staff, null);

            assertThat(response.getStatusCode().value()).as(status + " " + response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("DENTAL_INVALID_STATE");
            assertThat(count("visit_link", "appointment_id", id)).isZero();
        }
    }

    @Test
    void permissions_required() {
        String admin = adminToken();
        UUID id = idOf(book(admin, futureDay()));
        String reader = tokenWith("dental_it_link_reader", "dental.appointments.read");
        String writer = tokenWith("dental_it_link_writer", "dental.appointments.write");

        assertThat(post(linkPath(id), reader, null).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange(HttpMethod.DELETE, linkPath(id), reader, null).getStatusCode().value()).isEqualTo(403);
        assertThat(get(linkPath(id), writer).getStatusCode().value()).isEqualTo(403);
        assertThat(get(linkPath(id), reader).getStatusCode().value()).isEqualTo(200);
        assertThat(post(linkPath(id), null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(count("visit_link", "appointment_id", id)).isZero();
        assertThat(post(linkPath(id), writer, null).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void reschedule_keeps_form_and_moves_link_expiry() {
        String staff = adminToken();
        LocalDate day = futureDay();
        JsonNode booked = book(staff, day);
        UUID id = idOf(booked);
        String token = tokenOf(post(linkPath(id), staff, null));
        UUID scan = seedScan(id, Instant.now());
        UUID form = seedForm(id, scan);
        LocalDate newDay = day.plusDays(4);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("startsAt", at(newDay, 15, 0));

        ResponseEntity<String> moved = patch(APPOINTMENTS + "/" + id, staff, change, booked.path("version").asText());

        assertThat(moved.getStatusCode().value()).as(moved.getBody()).isEqualTo(200);
        assertThat(expiresAt(id)).isEqualTo(newDay.atTime(23, 59).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
        assertThat(resolveStatus(token)).as("same link still works").isEqualTo(200);
        assertThat(count("previsit_form", "id", form)).as("form stays").isEqualTo(1);
        assertThat(count("id_scan", "id", scan)).as("photo stays").isEqualTo(1);
    }

    @Test
    void status_changes_keep_link_and_cancel_deletes_form_and_photos() {
        String staff = adminToken();
        UUID arrived = idOf(book(staff, futureDay()));
        UUID cancelled = idOf(book(staff, futureDay()));
        UUID noShow = idOf(book(staff, futureDay()));
        String arrivedToken = tokenOf(post(linkPath(arrived), staff, null));
        String cancelledToken = tokenOf(post(linkPath(cancelled), staff, null));
        UUID scan = seedScan(cancelled, Instant.now());
        UUID form = seedForm(cancelled, scan);
        UUID keptScan = seedScan(arrived, Instant.now());
        UUID keptForm = seedForm(arrived, keptScan);

        assertThat(post(APPOINTMENTS + "/" + arrived + "/arrive", staff, null).getStatusCode().value()).isEqualTo(200);
        assertThat(post(APPOINTMENTS + "/" + cancelled + "/cancel", staff, null).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE dental.appointment SET starts_at = now() - interval '1 hour' WHERE id = ?", noShow);
        seedLink(noShow, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(post(APPOINTMENTS + "/" + noShow + "/no-show", staff, null).getStatusCode().value()).isEqualTo(200);

        assertThat(count("visit_link", "appointment_id", arrived)).as("arrive keeps the link").isEqualTo(1);
        assertThat(count("visit_link", "appointment_id", cancelled)).as("cancel keeps the link").isEqualTo(1);
        assertThat(count("visit_link", "appointment_id", noShow)).as("no-show keeps the link").isEqualTo(1);
        assertThat(resolveStatus(cancelledToken)).as("so the public page can say closed").isEqualTo(200);
        assertThat(resolveStatus(arrivedToken)).isEqualTo(200);
        assertThat(count("previsit_form", "id", form)).as("cancel deletes the form").isZero();
        assertThat(count("id_scan", "id", scan)).as("cancel deletes the photo").isZero();
        assertThat(count("previsit_form", "id", keptForm)).as("arrive keeps the form").isEqualTo(1);
        assertThat(count("id_scan", "id", keptScan)).as("arrive keeps the photo").isEqualTo(1);
    }

    @Test
    void upload_stores_digest_dedupes_and_caps() throws Exception {
        String staff = adminToken();
        UUID id = idOf(book(staff, futureDay()));
        byte[] image = CccdQrImages.jpeg(CccdQrImages.payload(), 1);

        Object first = upload(id, image);
        Object second = upload(id, image);

        UUID firstId = (UUID) accessor(first, "scanId");
        assertThat(accessor(first, "created")).isEqualTo(true);
        assertThat(accessor(second, "created")).isEqualTo(false);
        assertThat(accessor(second, "scanId")).isEqualTo(firstId);
        assertThat(accessor(first, "fields").toString()).as("fields never print").doesNotContain(CccdQrImages.ID_NUMBER);
        assertThat(count("id_scan", "appointment_id", id)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT content_type, size_bytes, qr_digest, uploaded_at FROM dental.id_scan WHERE id = ?", firstId);
        assertThat(row.get("content_type")).isEqualTo("image/jpeg");
        assertThat((String) row.get("qr_digest")).startsWith("idType=").doesNotContain(CccdQrImages.ID_NUMBER);
        assertThat(row.get("uploaded_at")).isNotNull();
        List<String> audits = jdbc.queryForList("""
            SELECT entity_type || '|' || after_json::text FROM audit_event
            WHERE plugin_id = 'com.crm.dental' AND op = 'ID_SCAN_UPLOAD' AND entity_id = ?
            """, String.class, firstId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).startsWith("DentalIdScan|{\"sizeBytes\"").doesNotContain(CccdQrImages.ID_NUMBER);

        for (int i = 0; i < 19; i++) {
            seedScan(id, Instant.now());
        }
        assertThat(count("id_scan", "appointment_id", id)).isEqualTo(20);
        assertThat(uploadStatus(id, CccdQrImages.jpeg(CccdQrImages.payload(), 2))).as("21st scan").isEqualTo("DENTAL_SCAN_LIMIT");
        assertThat(upload(id, image)).as("a known image is still answered at the cap").isNotNull();
        assertThat(uploadStatus(id, CccdQrImages.jpegWithoutQr(3))).isEqualTo("DENTAL_SCAN_UNREADABLE");
        assertThat(uploadStatus(id, new byte[] {1, 2, 3, 4})).isEqualTo("DENTAL_SCAN_UNSUPPORTED");
        jdbc.update("UPDATE dental.appointment SET status = 'CANCELLED' WHERE id = ?", id);
        assertThat(uploadStatus(id, CccdQrImages.jpeg(CccdQrImages.payload(), 4))).isEqualTo("DENTAL_VISIT_CLOSED");
        assertThat(uploadStatus(UUID.randomUUID(), image)).isEqualTo("DENTAL_NOT_FOUND");
    }

    @Test
    void photo_purged_after_30_days() {
        String staff = adminToken();
        UUID id = idOf(book(staff, futureDay()));
        Instant now = Instant.now();
        UUID old = seedScan(id, now.minus(30, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES));
        UUID young = seedScan(id, now.minus(29, ChronoUnit.DAYS));
        UUID form = seedForm(id, old);
        UUID longExpired = idOf(book(staff, futureDay()));
        UUID recentlyExpired = idOf(book(staff, futureDay()));
        UUID live = idOf(book(staff, futureDay()));
        seedLink(longExpired, now.minus(31, ChronoUnit.DAYS));
        seedLink(recentlyExpired, now.minus(29, ChronoUnit.DAYS));
        seedLink(live, now.plus(2, ChronoUnit.DAYS));
        long before = purgeAudits();

        int purged = purgeScansOlderThan(now.minus(30, ChronoUnit.DAYS));

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(count("id_scan", "id", old)).isZero();
        assertThat(count("id_scan", "id", young)).isEqualTo(1);
        assertThat(count("previsit_form", "id", form)).as("the form is kept").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id_scan_id FROM dental.previsit_form WHERE id = ?", UUID.class, form))
            .as("ON DELETE SET NULL").isNull();
        assertThat(count("visit_link", "appointment_id", longExpired)).isZero();
        assertThat(count("visit_link", "appointment_id", recentlyExpired)).isEqualTo(1);
        assertThat(count("visit_link", "appointment_id", live)).isEqualTo(1);
        // The hourly cron may purge in between, so only a delta of at least one is certain
        assertThat(purgeAudits()).isGreaterThanOrEqualTo(before + 1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT entity_type, entity_id, user_id, source_ip, after_json::text AS after
            FROM audit_event WHERE op = 'ID_SCAN_PURGE' AND plugin_id = 'com.crm.dental'
            """);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("entity_type")).isEqualTo("DentalIdScan");
            assertThat(row.get("entity_id")).isNull();
            assertThat(row.get("user_id")).isNull();
            assertThat(row.get("source_ip")).isNull();
            assertThat((String) row.get("after")).matches("\\{\"count\": \\d+}");
        });
        assertThat(rows).extracting(row -> row.get("after")).contains("{\"count\": " + purged + "}");

        assertThat(purgeScansOlderThan(Instant.EPOCH)).as("nothing is older than the epoch").isZero();
    }

    @Test
    void audit_has_no_token() {
        String staff = adminToken();
        UUID id = idOf(book(staff, futureDay()));
        String token = tokenOf(post(linkPath(id), staff, null));
        exchange(HttpMethod.DELETE, linkPath(id), staff, null);

        List<String> events = jdbc.queryForList("""
            SELECT op || '|' || entity_type || '|' || coalesce(before_json::text, '') || '|'
                   || coalesce(after_json::text, '') FROM audit_event
            WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op LIKE 'VISIT_LINK_%' ORDER BY op
            """, String.class, id);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).startsWith("VISIT_LINK_CREATE|Appointment||").contains("expiresAt");
        assertThat(events.get(1)).isEqualTo("VISIT_LINK_REVOKE|Appointment||");
        assertThat(String.join(" ", events)).doesNotContain(token).doesNotContain("nhakhoa").doesNotContain("http");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event WHERE entity_id = ? AND (after_json::text LIKE ? OR before_json::text LIKE ?)
            """, Integer.class, id, "%" + token + "%", "%" + token + "%")).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private long purgeAudits() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE op = 'ID_SCAN_PURGE' AND plugin_id = 'com.crm.dental'", Long.class);
    }

    private String tokenOf(ResponseEntity<String> created) {
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        String url = data(created).path("url").asText();
        assertThat(url).startsWith(BASE);
        return url.substring(BASE.length());
    }

    private byte[] storedHash(UUID appointmentId) {
        return jdbc.queryForObject("SELECT token_hash FROM dental.visit_link WHERE appointment_id = ?",
            byte[].class, appointmentId);
    }

    private Instant expiresAt(UUID appointmentId) {
        return jdbc.queryForObject("SELECT expires_at FROM dental.visit_link WHERE appointment_id = ?",
            Timestamp.class, appointmentId).toInstant();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code DentalIdScanService.upload(appointmentId, bytes)} of the running plugin, by reflection. */
    private Object upload(UUID appointmentId, byte[] bytes) throws ReflectiveOperationException {
        Object service = bean("dentalIdScanService");
        return service.getClass().getMethod("upload", UUID.class, byte[].class).invoke(service, appointmentId, bytes);
    }

    /** The code of the problem an upload throws. */
    private String uploadStatus(UUID appointmentId, byte[] bytes) throws ReflectiveOperationException {
        try {
            upload(appointmentId, bytes);
            return "OK";
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof com.crm.foundation.Plugin.PluginProblem problem) {
                return problem.code();
            }
            throw e;
        }
    }

    private static Object accessor(Object record, String name) throws ReflectiveOperationException {
        java.lang.reflect.Method method = record.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }
}
