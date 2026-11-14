package com.crm.foundation.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The guest's public check-in API ({@code /public/checkin}, token in {@code X-Checkin-Token}) over HTTP. */
// The default limit (60 requests a minute per client) would trip a whole test class that calls from one address.
@TestPropertySource(properties = "crm.public.rate-limit-per-minute=100000")
class PublicCheckinIT extends HotelITSupport {

    private static final String BASE = "https://checkin.mathisterben.org/#";
    private static final String PUBLIC = HOTEL_API + "/public/checkin";
    private static final String TOKEN_HEADER = "X-Checkin-Token";

    /** A confirmed reservation with a live link. */
    private record Stay(UUID reservationId, String guestToken, String staffToken, String roomTypeCode) {
    }

    @Test
    void summary_has_masked_names_and_no_identity_values() {
        Stay stay = stay(3);
        ResponseEntity<String> submitted = submit(stay, "079090123456", "Nguyễn Văn An", null, true);
        assertThat(submitted.getStatusCode().value()).as(submitted.getBody()).isEqualTo(201);

        ResponseEntity<String> response = pub(HttpMethod.GET, PUBLIC, stay.guestToken(), null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode data = data(response);
        assertThat(data.path("hotelName").asText()).isNotBlank();
        assertThat(data.path("roomTypeName").asText()).isEqualTo("Phòng " + stay.roomTypeCode());
        assertThat(data.path("checkIn").asText()).isEqualTo(hotelToday().plusDays(1).toString());
        assertThat(data.path("checkOut").asText()).isEqualTo(hotelToday().plusDays(3).toString());
        assertThat(data.path("adults").asInt()).isEqualTo(1);
        assertThat(data.path("maxGuests").asInt()).isEqualTo(3);
        assertThat(data.path("open").asBoolean()).isTrue();
        assertThat(data.path("guests")).hasSize(1);
        JsonNode guest = data.path("guests").get(0);
        assertThat(guest.path("id").asText()).isEqualTo(data(submitted).path("id").asText());
        assertThat(guest.path("maskedName").asText()).isEqualTo("N***** V** A*");
        assertThat(guest.path("documentType").asText()).isEqualTo("CCCD");
        assertThat(response.getBody()).doesNotContain("079090123456").doesNotContain("1990-05-17")
            .doesNotContain("Nguyễn").doesNotContain("Lê Lợi");
    }

    @Test
    void unknown_expired_revoked_tokens_same_404() {
        Stay expired = stay(2);
        jdbc.update("UPDATE hotel.guest_link SET expires_at = now() - interval '1 hour' WHERE reservation_id = ?",
            expired.reservationId());
        Stay revoked = stay(2);
        assertThat(exchange(HttpMethod.DELETE, link(revoked.reservationId()), revoked.staffToken(), null)
            .getStatusCode().value()).isEqualTo(204);
        String unknown = UUID.randomUUID().toString().replace("-", "");

        List<ResponseEntity<String>> responses = List.of(
            pub(HttpMethod.GET, PUBLIC, unknown, null),
            pub(HttpMethod.GET, PUBLIC, expired.guestToken(), null),
            pub(HttpMethod.GET, PUBLIC, revoked.guestToken(), null));

        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
            assertThat(json(response).path("detail").asText()).isEqualTo(json(responses.get(0)).path("detail").asText());
        }
        // the same for a write
        assertThat(pub(HttpMethod.POST, PUBLIC + "/guests", expired.guestToken(), body("1", "X", null, true))
            .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void missing_header_404() {
        Stay stay = stay(2);

        ResponseEntity<String> missing = pub(HttpMethod.GET, PUBLIC, null, null);
        ResponseEntity<String> blank = pub(HttpMethod.GET, PUBLIC, "   ", null);
        ResponseEntity<String> tooLong = pub(HttpMethod.GET, PUBLIC, "a".repeat(101), null);
        ResponseEntity<String> atLimit = pub(HttpMethod.GET, PUBLIC, "a".repeat(100), null);

        for (ResponseEntity<String> response : List.of(missing, blank, tooLong, atLimit)) {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        }
        assertThat(pub(HttpMethod.GET, PUBLIC, stay.guestToken(), null).getStatusCode().value()).isEqualTo(200);
        assertThat(pub(HttpMethod.POST, PUBLIC + "/guests", null, body("1", "X", null, true)).getStatusCode().value())
            .isEqualTo(404);
        assertThat(pub(HttpMethod.DELETE, PUBLIC + "/guests/" + UUID.randomUUID(), null, null).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void upload_via_link_prefills() {
        Stay stay = stay(2);
        byte[] image = CccdQrImages.jpeg(CccdQrImages.payload());

        ResponseEntity<String> first = upload(stay.guestToken(), image);

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(201);
        JsonNode fields = data(first).path("fields");
        assertThat(fields.path("idType").asText()).isEqualTo("CCCD");
        assertThat(fields.path("idNumber").asText()).isEqualTo(CccdQrImages.ID_NUMBER);
        assertThat(fields.path("fullName").asText()).isEqualTo(CccdQrImages.FULL_NAME);
        assertThat(fields.path("dateOfBirth").asText()).isEqualTo("1990-05-17");
        UUID scanId = UUID.fromString(data(first).path("scanId").asText());
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT reservation_id, uploaded_by, qr_digest FROM hotel.id_scan WHERE id = ?", scanId);
        assertThat(row.get("reservation_id")).isEqualTo(stay.reservationId());
        assertThat(row.get("uploaded_by")).isNull();
        String digest = (String) row.get("qr_digest");
        assertThat(digest).startsWith("idType=").contains(";idNumber=").contains(";address=");
        assertThat(digest).doesNotContain(CccdQrImages.ID_NUMBER).doesNotContain("NGUYEN");

        ResponseEntity<String> again = upload(stay.guestToken(), image);

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(data(again).path("scanId").asText()).isEqualTo(scanId.toString());
        assertThat(jdbc.queryForObject("SELECT qr_digest FROM hotel.id_scan WHERE id = ?", String.class, scanId))
            .isEqualTo(digest);
    }

    @Test
    void staff_upload_stores_qr_digest() {
        Stay stay = stay(2);
        MultiValueMap<String, Object> form = form(CccdQrImages.jpeg(CccdQrImages.payload()));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(stay.staffToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.postForEntity(
            HOTEL_API + "/reservations/" + stay.reservationId() + "/id-scans", new HttpEntity<>(form, headers),
            String.class);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        UUID scanId = UUID.fromString(data(response).path("scanId").asText());
        assertThat(jdbc.queryForObject("SELECT qr_digest FROM hotel.id_scan WHERE id = ?", String.class, scanId))
            .startsWith("idType=");
    }

    @Test
    void submit_with_scan_and_without() {
        Stay stay = stay(3);
        UUID scanId = scanOf(stay, CccdQrImages.payload());

        ResponseEntity<String> withScan = submit(stay, CccdQrImages.ID_NUMBER, CccdQrImages.FULL_NAME, scanId, true);
        ResponseEntity<String> without = submit(stay, "C1234567", "Alice Smith", null, true);

        assertThat(withScan.getStatusCode().value()).as(withScan.getBody()).isEqualTo(201);
        assertThat(without.getStatusCode().value()).as(without.getBody()).isEqualTo(201);
        assertThat(data(withScan).path("maskedName").asText()).isEqualTo("N***** V** A*");
        assertThat(data(withScan).path("documentType").asText()).isEqualTo("CCCD");
        assertThat(data(without).path("maskedName").asText()).isEqualTo("A**** S****");
        assertThat(data(without).path("documentType").asText()).isEqualTo("CCCD");
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT id_scan_id, edited_fields, consent_at, notice_version, submitted_at FROM hotel.pre_arrival_guest "
                + "WHERE id = ?", UUID.fromString(data(withScan).path("id").asText()));
        assertThat(row.get("id_scan_id")).isEqualTo(scanId);
        assertThat(row.get("edited_fields")).isNull();
        assertThat(row.get("consent_at")).isNotNull();
        assertThat(row.get("notice_version")).isEqualTo("2026-11-14");
        assertThat(row.get("submitted_at")).isNotNull();
        assertThat(count(stay)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id_scan_id FROM hotel.pre_arrival_guest WHERE id = ?", UUID.class,
            UUID.fromString(data(without).path("id").asText()))).isNull();
    }

    @Test
    void consent_required_400() {
        Stay stay = stay(2);

        ResponseEntity<String> response = submit(stay, "079090123456", "Nguyen Van An", null, false);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_GUEST");
        assertThat(count(stay)).isZero();
    }

    @Test
    void same_document_twice_returns_existing() {
        Stay stay = stay(3);

        ResponseEntity<String> first = submit(stay, "C7654321", "Bob Jones", null, true);
        ResponseEntity<String> second = submit(stay, "C7654321", "Bob Jones", null, true);

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(200);
        assertThat(data(second).path("id").asText()).isEqualTo(data(first).path("id").asText());
        assertThat(data(second).path("maskedName").asText()).isEqualTo("B** J****");
        assertThat(count(stay)).isEqualTo(1);
    }

    @Test
    void parallel_submits_never_exceed_max_guests() throws Exception {
        Stay stay = stay(2);
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String number = "P" + (1000 + i);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return submit(stay, number, "Parallel Guest", null, true);
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get();
                statuses.add(response.getStatusCode().value());
                if (response.getStatusCode().value() != 201) {
                    codes.add(json(response).path("code").asText());
                }
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(2);
            assertThat(statuses).filteredOn(status -> status == 400).hasSize(2);
            assertThat(codes).containsOnly("HOTEL_TOO_MANY_GUESTS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(stay)).isEqualTo(2);
    }

    @Test
    void scan_of_other_reservation_or_reused_scan_409_mismatch() {
        Stay one = stay(3);
        Stay other = stay(3);
        UUID scanOfOther = scanOf(other, CccdQrImages.payload());
        UUID scanOfOne = scanOf(one, CccdQrImages.payload("079090999999", "TRAN THI BE"));

        ResponseEntity<String> foreign = submit(one, "A1", "Foreign Scan", scanOfOther, true);
        ResponseEntity<String> unknown = submit(one, "A2", "Unknown Scan", UUID.randomUUID(), true);
        ResponseEntity<String> ok = submit(one, "079090999999", "TRAN THI BE", scanOfOne, true);
        ResponseEntity<String> reused = submit(one, "A3", "Reused Scan", scanOfOne, true);

        for (ResponseEntity<String> response : List.of(foreign, unknown, reused)) {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_SCAN_MISMATCH");
        }
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(201);
        assertThat(count(one)).isEqualTo(1);
        assertThat(count(other)).isZero();
    }

    @Test
    void edited_fields_lists_changed_names() {
        Stay stay = stay(3);
        UUID scanId = scanOf(stay, CccdQrImages.payload());
        Map<String, Object> identity = identity("CCCD", CccdQrImages.ID_NUMBER, "Nguyen Van An");
        identity.put("address", "99 Tran Hung Dao, Hue");
        identity.put("idScanId", scanId);

        ResponseEntity<String> response = pub(HttpMethod.POST, PUBLIC + "/guests", stay.guestToken(),
            Map.of("identity", identity, "consent", true));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT edited_fields FROM hotel.pre_arrival_guest WHERE id = ?", String.class,
            UUID.fromString(data(response).path("id").asText()))).isEqualTo("fullName,address");
    }

    @Test
    void delete_submission_deletes_its_scan() {
        Stay stay = stay(3);
        UUID scanId = scanOf(stay, CccdQrImages.payload());
        UUID id = UUID.fromString(data(submit(stay, CccdQrImages.ID_NUMBER, CccdQrImages.FULL_NAME, scanId, true))
            .path("id").asText());
        UUID otherScan = scanOf(stay, CccdQrImages.payload("079090777777", "LE VAN CAM"));

        ResponseEntity<String> deleted = pub(HttpMethod.DELETE, PUBLIC + "/guests/" + id, stay.guestToken(), null);

        assertThat(deleted.getStatusCode().value()).as(deleted.getBody()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.pre_arrival_guest WHERE id = ?", Integer.class, id))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.id_scan WHERE id = ?", Integer.class, scanId))
            .isZero();
        // an unrelated, unsubmitted scan stays
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.id_scan WHERE id = ?", Integer.class, otherScan))
            .isEqualTo(1);
        assertThat(pub(HttpMethod.DELETE, PUBLIC + "/guests/" + id, stay.guestToken(), null).getStatusCode().value())
            .isEqualTo(404);
        // another reservation's link cannot delete this one's submission
        Stay mine = stay(3);
        UUID kept = UUID.fromString(data(submit(mine, "K1", "Keep Me", null, true)).path("id").asText());
        Stay thief = stay(3);
        assertThat(pub(HttpMethod.DELETE, PUBLIC + "/guests/" + kept, thief.guestToken(), null).getStatusCode().value())
            .isEqualTo(404);
        assertThat(count(mine)).isEqualTo(1);
    }

    @Test
    void closed_after_checkin_or_cancel_409() {
        for (String status : List.of("CHECKED_IN", "CANCELLED")) {
            Stay stay = stay(3);
            UUID submitted = UUID.fromString(data(submit(stay, "Z1", "Before Closing", null, true)).path("id").asText());
            jdbc.update("UPDATE hotel.reservation SET status = ? WHERE id = ?", status, stay.reservationId());

            ResponseEntity<String> summary = pub(HttpMethod.GET, PUBLIC, stay.guestToken(), null);
            ResponseEntity<String> post = submit(stay, "Z2", "After Closing", null, true);
            ResponseEntity<String> upload = upload(stay.guestToken(), CccdQrImages.jpeg(CccdQrImages.payload()));
            ResponseEntity<String> delete = pub(HttpMethod.DELETE, PUBLIC + "/guests/" + submitted, stay.guestToken(), null);

            assertThat(summary.getStatusCode().value()).as(status + summary.getBody()).isEqualTo(200);
            assertThat(data(summary).path("open").asBoolean()).isFalse();
            for (ResponseEntity<String> response : List.of(post, upload, delete)) {
                assertThat(response.getStatusCode().value()).as(status + response.getBody()).isEqualTo(409);
                assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_CHECKIN_CLOSED");
            }
            assertThat(count(stay)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.id_scan WHERE reservation_id = ?", Integer.class,
                stay.reservationId())).isZero();
        }
    }

    @Test
    void no_store_and_noindex_on_success_and_error() {
        Stay stay = stay(2);

        List<ResponseEntity<String>> responses = List.of(
            pub(HttpMethod.GET, PUBLIC, stay.guestToken(), null),
            submit(stay, "H1", "Header Check", null, true),
            submit(stay, "H2", "No Consent", null, false),
            pub(HttpMethod.GET, PUBLIC, "nope", null),
            pub(HttpMethod.GET, PUBLIC, null, null),
            upload(stay.guestToken(), "garbage".getBytes()));

        assertThat(responses.stream().map(r -> r.getStatusCode().value()).toList())
            .containsExactly(200, 201, 400, 404, 404, 415);
        for (ResponseEntity<String> response : responses) {
            assertThat(response.getHeaders().getCacheControl()).as(response.getBody()).contains("no-store");
            assertThat(response.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex");
        }
    }

    @Test
    void audit_has_no_identity_or_token() {
        Stay stay = stay(3);
        UUID scanId = scanOf(stay, CccdQrImages.payload());
        UUID id = UUID.fromString(data(submit(stay, CccdQrImages.ID_NUMBER, CccdQrImages.FULL_NAME, scanId, true))
            .path("id").asText());
        pub(HttpMethod.DELETE, PUBLIC + "/guests/" + id, stay.guestToken(), null);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT op, user_id, entity_type, entity_id, after_json::text AS after FROM audit_event
            WHERE plugin_id = 'com.crm.hotel' AND (entity_id = ? OR entity_id = ?) ORDER BY occurred_at
            """, stay.reservationId(), scanId);

        assertThat(rows).extracting(r -> r.get("op")).contains("ID_SCAN_UPLOAD", "PRE_ARRIVAL_SUBMIT", "PRE_ARRIVAL_DELETE");
        for (Map<String, Object> row : rows) {
            if (String.valueOf(row.get("op")).startsWith("PRE_ARRIVAL_") || "ID_SCAN_UPLOAD".equals(row.get("op"))) {
                assertThat(row.get("user_id")).as(String.valueOf(row.get("op"))).isNull();
            }
            if (String.valueOf(row.get("op")).startsWith("PRE_ARRIVAL_")) {
                assertThat(row.get("entity_type")).isEqualTo("Reservation");
                assertThat(row.get("entity_id")).isEqualTo(stay.reservationId());
                assertThat(parse(row.get("after"))).isEqualTo(Map.of("submissionId", id.toString()));
            }
        }
        String everything = rows.toString();
        assertThat(everything).doesNotContain(CccdQrImages.ID_NUMBER).doesNotContain("NGUYEN")
            .doesNotContain("1990-05-17").doesNotContain(stay.guestToken()).doesNotContain("Le Loi");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event WHERE after_json::text LIKE ? OR after_json::text LIKE ?
            """, Integer.class, "%" + stay.guestToken() + "%", "%" + CccdQrImages.ID_NUMBER + "%")).isZero();
        // no change-feed entry carries a pre-arrival row either
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_event WHERE entity_id = ?", Integer.class, id))
            .isZero();
    }

    @Test
    void staff_path_with_checkin_token_still_401() {
        Stay stay = stay(2);

        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
            ResponseEntity<String> list = exchange(method, HOTEL_API + "/reservations", null, null,
                TOKEN_HEADER, stay.guestToken());
            ResponseEntity<String> linkStatus = exchange(method, link(stay.reservationId()), null, null,
                TOKEN_HEADER, stay.guestToken());
            ResponseEntity<String> scan = exchange(method,
                HOTEL_API + "/reservations/" + stay.reservationId() + "/id-scans", null, null,
                TOKEN_HEADER, stay.guestToken());
            assertThat(list.getStatusCode().value()).as(method + " " + list.getBody()).isEqualTo(401);
            assertThat(linkStatus.getStatusCode().value()).isEqualTo(401);
            assertThat(scan.getStatusCode().value()).isEqualTo(401);
        }
        assertThat(exchange(HttpMethod.GET, HOTEL_API + "/reservations/" + stay.reservationId() + "/stay-guests", null,
            null, TOKEN_HEADER, stay.guestToken()).getStatusCode().value()).isEqualTo(401);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String link(UUID reservationId) {
        return HOTEL_API + "/reservations/" + reservationId + "/guest-link";
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /** A confirmed reservation of one adult for tomorrow..+3 days, in a room type sleeping {@code maxGuests}. */
    private Stay stay(int maxGuests) {
        String staff = adminToken();
        String code = "PC" + tag();
        UUID room = newRoom(staff, "PC" + tag(), newRoomType(staff, code, maxGuests, 900_000, true), true);
        UUID reservation = book(staff, room, hotelToday().plusDays(1), hotelToday().plusDays(3), 1);
        ResponseEntity<String> created = post(link(reservation), staff, null);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        String url = data(created).path("url").asText();
        assertThat(url).startsWith(BASE);
        return new Stay(reservation, url.substring(BASE.length()), staff, code);
    }

    private ResponseEntity<String> pub(HttpMethod method, String path, String guestToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (guestToken != null) {
            headers.set(TOKEN_HEADER, guestToken);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static Map<String, Object> identity(String idType, String idNumber, String name) {
        return new LinkedHashMap<>(adult(idType, idNumber, name));
    }

    private static Map<String, Object> body(String idNumber, String name, UUID scanId, boolean consent) {
        Map<String, Object> identity = identity("CCCD", idNumber, name);
        identity.put("idScanId", scanId);
        if (scanId != null) {
            // what the QR of CccdQrImages says, so a form filled from a scan has no edits
            identity.put("address", "12 Le Loi, Phu Hoi, Hue");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identity", identity);
        body.put("consent", consent);
        return body;
    }

    private ResponseEntity<String> submit(Stay stay, String idNumber, String name, UUID scanId, boolean consent) {
        return pub(HttpMethod.POST, PUBLIC + "/guests", stay.guestToken(), body(idNumber, name, scanId, consent));
    }

    private static MultiValueMap<String, Object> form(byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "cccd.jpg";
            }
        });
        return form;
    }

    private ResponseEntity<String> upload(String guestToken, byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        if (guestToken != null) {
            headers.set(TOKEN_HEADER, guestToken);
        }
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(PUBLIC + "/id-scans", new HttpEntity<>(form(bytes), headers), String.class);
    }

    /** Uploads a CCCD image for the stay through its link (asserting success) and returns the scan id. */
    private UUID scanOf(Stay stay, String payload) {
        ResponseEntity<String> uploaded = upload(stay.guestToken(), CccdQrImages.jpeg(payload));
        assertThat(uploaded.getStatusCode().value()).as(uploaded.getBody()).isEqualTo(201);
        return UUID.fromString(data(uploaded).path("scanId").asText());
    }

    private int count(Stay stay) {
        return jdbc.queryForObject("SELECT count(*) FROM hotel.pre_arrival_guest WHERE reservation_id = ?",
            Integer.class, stay.reservationId());
    }

    private Map<String, Object> parse(Object text) {
        try {
            return objectMapper.readValue(String.valueOf(text), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
