package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Linking CCCD scans to identity records at check-in and correction, viewing them, undo. Over HTTP. */
class IdScanLinkIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void check_in_with_scan_links_it() {
        String token = adminToken();
        UUID id = booked(token, 1);
        UUID scan = upload(token, id, "079090200001", "NGUYEN MOT");

        ResponseEntity<String> response = checkIn(token, id, List.of(adultWithScan("079090200001", "Nguyen Mot", scan)));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        assertThat(jdbc.queryForObject("SELECT stay_guest_id FROM hotel.id_scan WHERE id = ?", UUID.class, scan))
            .isEqualTo(stayGuest);
        ResponseEntity<byte[]> image = image(token, id, stayGuest);
        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(image.getHeaders().getCacheControl()).contains("no-store");
        byte[] stored = jdbc.queryForObject("SELECT image FROM hotel.id_scan WHERE id = ?", byte[].class, scan);
        assertThat(image.getBody()).isEqualTo(stored);
        assertThat(image.getBody()[0] & 0xFF).isEqualTo(0xFF);
        // the audit names the scan id only
        String audit = jdbc.queryForObject("""
            SELECT after_json::text FROM audit_event
            WHERE plugin_id = 'com.crm.hotel' AND op = 'RESERVATION_CHECK_IN' AND entity_id = ?
            """, String.class, id);
        assertThat(audit).contains(scan.toString()).doesNotContain("079090200001").doesNotContain("Nguyen Mot");
    }

    @Test
    void same_scan_for_two_adults_is_409() {
        String token = adminToken();
        UUID id = booked(token, 2);
        UUID scan = upload(token, id, "079090200002", "NGUYEN HAI");

        ResponseEntity<String> response = checkIn(token, id, List.of(
            adultWithScan("079090200002", "Nguyen Hai", scan), adultWithScan("079090200003", "Tran Ba", scan)));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_SCAN_MISMATCH");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(stayGuestIds(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT stay_guest_id FROM hotel.id_scan WHERE id = ?", UUID.class, scan))
            .isNull();
    }

    @Test
    void scan_from_other_reservation_is_409() {
        String token = adminToken();
        UUID id = booked(token, 1);
        UUID other = booked(token, 1);
        UUID foreign = upload(token, other, "079090200004", "NGUYEN BON");

        ResponseEntity<String> foreignScan =
            checkIn(token, id, List.of(adultWithScan("079090200004", "Nguyen Bon", foreign)));
        ResponseEntity<String> unknownScan =
            checkIn(token, id, List.of(adultWithScan("079090200004", "Nguyen Bon", UUID.randomUUID())));

        assertThat(foreignScan.getStatusCode().value()).as(foreignScan.getBody()).isEqualTo(409);
        assertThat(json(foreignScan).path("code").asText()).isEqualTo("HOTEL_SCAN_MISMATCH");
        assertThat(unknownScan.getStatusCode().value()).as(unknownScan.getBody()).isEqualTo(409);
        assertThat(json(unknownScan).path("code").asText()).isEqualTo("HOTEL_SCAN_MISMATCH");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(stayGuestIds(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT stay_guest_id FROM hotel.id_scan WHERE id = ?", UUID.class, foreign))
            .isNull();
    }

    @Test
    void scan_linked_to_another_guest_is_409_on_correction() {
        String token = adminToken();
        UUID id = booked(token, 2);
        UUID first = upload(token, id, "079090200005", "NGUYEN NAM");
        assertThat(checkIn(token, id, List.of(
            adultWithScan("079090200005", "Nguyen Nam", first), adult("CCCD", "079090200006", "Tran Sau")))
            .getStatusCode().value()).isEqualTo(200);
        UUID second = stayGuestIds(id).stream()
            .filter(guest -> !guest.equals(linkedGuest(first))).findFirst().orElseThrow();

        ResponseEntity<String> response = put(RESERVATIONS + "/" + id + "/stay-guests/" + second, token,
            adultWithScan("079090200006", "Tran Sau", first), "0");

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_SCAN_MISMATCH");
        assertThat(linkedGuest(first)).isNotEqualTo(second);
    }

    @Test
    void image_view_200_with_stays_read_403_without() {
        String admin = adminToken();
        UUID id = booked(admin, 1);
        UUID scan = upload(admin, id, "079090200007", "NGUYEN BAY");
        assertThat(checkIn(admin, id, List.of(adultWithScan("079090200007", "Nguyen Bay", scan)))
            .getStatusCode().value()).isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        String reader = tokenWith("hotel_it_scan_reader", "hotel.stays.read");
        String noRead = tokenWith("hotel_it_scan_noread", "hotel.reservations.read", "hotel.stays.checkin");

        assertThat(image(reader, id, stayGuest).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<byte[]> forbidden = image(noRead, id, stayGuest);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(image(null, id, stayGuest).getStatusCode().value()).isEqualTo(401);
        // a stay guest without scan, another reservation, unknown ids
        UUID other = booked(admin, 1);
        assertThat(checkIn(admin, other, List.of(adult("CCCD", "079090200008", "Khong Anh")))
            .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<byte[]> noScan = image(admin, other, stayGuestIds(other).get(0));
        assertThat(noScan.getStatusCode().value()).isEqualTo(404);
        assertThat(image(admin, other, stayGuest).getStatusCode().value()).isEqualTo(404);
        assertThat(image(admin, id, UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> notFound = get(RESERVATIONS + "/" + other + "/stay-guests/" + stayGuest + "/id-scan", admin);
        assertThat(json(notFound).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void correction_with_new_scan_deletes_old() {
        String token = adminToken();
        UUID id = booked(token, 1);
        UUID oldScan = upload(token, id, "079090200009", "NGUYEN CHIN");
        UUID newScan = upload(token, id, "079090200009", "NGUYEN CHIN ANH");
        assertThat(checkIn(token, id, List.of(adultWithScan("079090200009", "Nguyen Chin", oldScan)))
            .getStatusCode().value()).isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        String path = RESERVATIONS + "/" + id + "/stay-guests/" + stayGuest;

        ResponseEntity<String> replaced = put(path, token, adultWithScan("079090200009", "Nguyen Chin", newScan), "0");

        assertThat(replaced.getStatusCode().value()).as(replaced.getBody()).isEqualTo(200);
        assertThat(scanExists(oldScan)).isFalse();
        assertThat(linkedGuest(newScan)).isEqualTo(stayGuest);
        // the same scan again is a no-op; no scan keeps the current one
        assertThat(put(path, token, adultWithScan("079090200009", "Nguyen Chin", newScan), "0")
            .getStatusCode().value()).isEqualTo(200);
        assertThat(put(path, token, adult("CCCD", "079090200009", "Nguyen Chin"), "0")
            .getStatusCode().value()).isEqualTo(200);
        assertThat(linkedGuest(newScan)).isEqualTo(stayGuest);
        assertThat(image(token, id, stayGuest).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.id_scan WHERE stay_guest_id = ?", Integer.class, stayGuest)).isEqualTo(1);
        // the correction audit names the scan id, never identity values
        List<String> audits = jdbc.queryForList("""
            SELECT after_json::text FROM audit_event
            WHERE plugin_id = 'com.crm.hotel' AND op = 'STAY_GUEST_CORRECT' AND after_json ->> 'stayGuestId' = ?
            """, String.class, stayGuest.toString());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains(newScan.toString()).doesNotContain("079090200009");
    }

    @Test
    void undo_check_in_deletes_linked_scans() {
        String token = adminToken();
        UUID id = booked(token, 2);
        UUID one = upload(token, id, "079090200010", "NGUYEN MUOI");
        UUID two = upload(token, id, "079090200011", "TRAN MUOI MOT");
        assertThat(checkIn(token, id, List.of(
            adultWithScan("079090200010", "Nguyen Muoi", one), adultWithScan("079090200011", "Tran Muoi Mot", two)))
            .getStatusCode().value()).isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);

        ResponseEntity<String> undone = post(RESERVATIONS + "/" + id + "/undo-check-in", token, null);

        assertThat(undone.getStatusCode().value()).as(undone.getBody()).isEqualTo(200);
        assertThat(stayGuestIds(id)).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.id_scan WHERE reservation_id = ?", Integer.class, id)).isZero();
        assertThat(image(token, id, stayGuest).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void check_in_with_scan_purged_meanwhile_is_409_and_saves_nothing() {
        String token = adminToken();
        UUID id = booked(token, 1);
        UUID scan = upload(token, id, "079090200013", "NGUYEN MUOI BA");
        // the hourly purge deletes the row between the upload and the check-in (a future cutoff catches it)
        assertThat(purgeScansOlderThan(java.time.Instant.now().plusSeconds(3600))).isGreaterThanOrEqualTo(1);

        ResponseEntity<String> response = checkIn(token, id, List.of(adultWithScan("079090200013", "Nguyen Muoi Ba", scan)));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_SCAN_MISMATCH");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(stayGuestIds(id)).isEmpty();
    }

    @Test
    void check_in_without_scan_still_works() {
        String token = adminToken();
        UUID id = booked(token, 1);

        ResponseEntity<String> response = checkIn(token, id, List.of(adult("CCCD", "079090200012", "Khong Scan")));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(stayGuestIds(id)).hasSize(1);
        assertThat(image(token, id, stayGuestIds(id).get(0)).getStatusCode().value()).isEqualTo(404);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private UUID booked(String token, int adults) {
        UUID room = newRoom(token, "SL" + tag(), newRoomType(token, "SL" + tag(), 3, 900_000, true), true);
        LocalDate today = hotelToday();
        return book(token, room, today, today.plusDays(1), adults);
    }

    private static Map<String, Object> adultWithScan(String idNumber, String name, UUID scanId) {
        Map<String, Object> adult = adult("CCCD", idNumber, name);
        adult.put("idScanId", scanId);
        return adult;
    }

    /** Uploads a CCCD photo through the real endpoint (asserting 201 or 200) and returns the scan id. */
    private UUID upload(String token, UUID reservationId, String idNumber, String name) {
        byte[] bytes = CccdQrImages.jpeg(CccdQrImages.payload(idNumber, name));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "cccd.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = rest.postForEntity(
            RESERVATIONS + "/" + reservationId + "/id-scans", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return UUID.fromString(data(response).path("scanId").asText());
    }

    private ResponseEntity<String> checkIn(String token, UUID id, List<Map<String, Object>> adults) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("adults", adults);
        body.put("roomId", null);
        return post(RESERVATIONS + "/" + id + "/check-in", token, body);
    }

    private ResponseEntity<byte[]> image(String token, UUID reservationId, UUID stayGuestId) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(RESERVATIONS + "/" + reservationId + "/stay-guests/" + stayGuestId + "/id-scan",
            HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private List<UUID> stayGuestIds(UUID reservationId) {
        return jdbc.queryForList(
            "SELECT id FROM hotel.stay_guest WHERE reservation_id = ? ORDER BY full_name, id", UUID.class, reservationId);
    }

    private UUID linkedGuest(UUID scanId) {
        return jdbc.queryForObject("SELECT stay_guest_id FROM hotel.id_scan WHERE id = ?", UUID.class, scanId);
    }

    private boolean scanExists(UUID scanId) {
        return jdbc.queryForObject("SELECT count(*) FROM hotel.id_scan WHERE id = ?", Integer.class, scanId) > 0;
    }

    private String status(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM hotel.reservation WHERE id = ?", String.class, reservationId);
    }

    private String tokenWith(String username, String... permissions) {
        User user = userRepository.findByUsername(username).orElseGet(() -> {
            User created = new User();
            created.setUsername(username);
            created.setEmail(username + "@example.com");
            created.setPassword(BCrypt.hashpw("irrelevant password", BCrypt.gensalt()));
            return userRepository.save(created);
        });
        return tokens.issueAccessToken(user, Set.of(permissions)).token();
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
