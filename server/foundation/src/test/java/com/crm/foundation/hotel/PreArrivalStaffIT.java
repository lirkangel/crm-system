package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What staff see of the guests' pre-arrival submissions, and what is cleared at check-in and cancellation. */
@TestPropertySource(properties = "crm.public.rate-limit-per-minute=100000")
class PreArrivalStaffIT extends HotelITSupport {

    private static final String BASE = "https://checkin.mathisterben.org/#";
    private static final String PUBLIC = HOTEL_API + "/public/checkin";
    private static final String TOKEN_HEADER = "X-Checkin-Token";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    private record Stay(UUID reservationId, String guestToken, String staffToken) {
    }

    @Test
    void staff_reads_full_fields_and_scan_id() {
        Stay stay = stay(0, 3);
        UUID scan = scanOf(stay, CccdQrImages.payload());
        // the guest changes the name after the scan filled the form
        assertThat(submit(stay, CccdQrImages.ID_NUMBER, "Nguyen Van An", scan).getStatusCode().value()).isEqualTo(201);
        assertThat(submit(stay, "X9876543", "Marie Curie", null, "PASSPORT").getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> response = get(guests(stay), stay.staffToken());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        JsonNode list = data(response);
        assertThat(list).hasSize(2);
        JsonNode withScan = null;
        JsonNode withoutScan = null;
        for (JsonNode view : list) {
            if (view.path("idNumber").asText().equals(CccdQrImages.ID_NUMBER)) {
                withScan = view;
            } else {
                withoutScan = view;
            }
        }
        assertThat(withScan.path("idScanId").asText()).isEqualTo(scan.toString());
        assertThat(withScan.path("idType").asText()).isEqualTo("CCCD");
        assertThat(withScan.path("fullName").asText()).isEqualTo("Nguyen Van An");
        assertThat(withScan.path("dateOfBirth").asText()).isEqualTo("1990-05-17");
        assertThat(withScan.path("nationality").asText()).isEqualTo("VN");
        assertThat(withScan.path("address").asText()).isEqualTo("12 Le Loi, Phu Hoi, Hue");
        assertThat(withScan.path("id").asText()).isNotBlank();
        assertThat(withScan.path("consentAt").asText()).isNotBlank();
        assertThat(withScan.path("submittedAt").asText()).isNotBlank();
        assertThat(withScan.has("visaNumber")).isTrue();
        assertThat(withScan.has("entryDate")).isTrue();
        assertThat(withScan.path("editedFields")).extracting(JsonNode::asText).containsExactly("fullName");
        assertThat(withoutScan.path("idScanId").isNull()).isTrue();
        assertThat(withoutScan.path("editedFields").isArray()).isTrue();
        assertThat(withoutScan.path("editedFields")).isEmpty();
    }

    @Test
    void staff_sees_scan_image_before_checkin() {
        Stay stay = stay(0, 3);
        UUID scan = scanOf(stay, CccdQrImages.payload());
        UUID guest = UUID.fromString(data(submit(stay, CccdQrImages.ID_NUMBER, "Nguyen Van An", scan)).path("id").asText());
        UUID noScanGuest = UUID.fromString(
            data(submit(stay, "X9876543", "Marie Curie", null, "PASSPORT")).path("id").asText());

        ResponseEntity<byte[]> image = image(stay.staffToken(), stay.reservationId(), guest);

        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(image.getHeaders().getCacheControl()).contains("no-store");
        byte[] stored = jdbc.queryForObject("SELECT image FROM hotel.id_scan WHERE id = ?", byte[].class, scan);
        assertThat(image.getBody()).isEqualTo(stored);
        assertThat(image(stay.staffToken(), stay.reservationId(), noScanGuest).getStatusCode().value()).isEqualTo(404);
        // a submission asked for under another reservation is not found
        Stay other = stay(0, 3);
        assertThat(image(other.staffToken(), other.reservationId(), guest).getStatusCode().value()).isEqualTo(404);
        assertThat(image(stay.staffToken(), stay.reservationId(), UUID.randomUUID()).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void stays_read_required_403() {
        Stay stay = stay(0, 3);
        UUID scan = scanOf(stay, CccdQrImages.payload());
        UUID guest = UUID.fromString(data(submit(stay, CccdQrImages.ID_NUMBER, "Nguyen Van An", scan)).path("id").asText());
        String none = tokenWith("hotel_it_prearr_none", "hotel.reservations.read");
        String reader = tokenWith("hotel_it_prearr_reader", "hotel.stays.read");

        assertThat(get(guests(stay), none).getStatusCode().value()).isEqualTo(403);
        assertThat(image(none, stay.reservationId(), guest).getStatusCode().value()).isEqualTo(403);
        assertThat(get(guests(stay), reader).getStatusCode().value()).isEqualTo(200);
        assertThat(image(reader, stay.reservationId(), guest).getStatusCode().value()).isEqualTo(200);
        assertThat(get(HOTEL_API + "/reservations/" + UUID.randomUUID() + "/pre-arrival-guests", reader)
            .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void arrivals_list_shows_pre_arrival_count() {
        Stay withTwo = stay(1, 3);
        Stay withNone = stay(1, 3);
        submit(withTwo, "079090000001", "Guest One", null);
        submit(withTwo, "079090000002", "Guest Two", null);
        LocalDate date = hotelToday().plusDays(1);

        ResponseEntity<String> arrivals = get(HOTEL_API + "/front-desk/arrivals?date=" + date, withTwo.staffToken());

        assertThat(arrivals.getStatusCode().value()).as(arrivals.getBody()).isEqualTo(200);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode entry : data(arrivals)) {
            counts.put(entry.path("reservation").path("id").asText(), entry.path("preArrivalAdults").asInt(-1));
        }
        assertThat(counts).containsEntry(withTwo.reservationId().toString(), 2)
            .containsEntry(withNone.reservationId().toString(), 0);
        // the other lists carry the field too, as zero
        for (String path : List.of("/in-house", "/departures")) {
            for (JsonNode entry : data(get(HOTEL_API + "/front-desk" + path, withTwo.staffToken()))) {
                assertThat(entry.path("preArrivalAdults").asInt(-1)).as(path).isZero();
            }
        }
    }

    @Test
    void checkin_with_prefilled_request_links_scan_and_clears_rows() {
        Stay stay = stay(0, 3);
        UUID linkedScan = scanOf(stay, CccdQrImages.payload());
        UUID spareScan = scanOf(stay, CccdQrImages.payload("079090777777", "TRAN THI BICH"));
        assertThat(submit(stay, CccdQrImages.ID_NUMBER, CccdQrImages.FULL_NAME, linkedScan).getStatusCode().value())
            .isEqualTo(201);
        assertThat(submit(stay, "079090777777", "TRAN THI BICH", spareScan).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> prefill = get(guests(stay), stay.staffToken());
        JsonNode first = null;
        for (JsonNode view : data(prefill)) {
            if (view.path("idNumber").asText().equals(CccdQrImages.ID_NUMBER)) {
                first = view;
            }
        }
        Map<String, Object> adult = adult("CCCD", first.path("idNumber").asText(), first.path("fullName").asText());
        adult.put("address", first.path("address").asText());
        adult.put("idScanId", linkedScan);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adults", List.of(adult));
        body.put("roomId", null);

        ResponseEntity<String> checkedIn = post(HOTEL_API + "/reservations/" + stay.reservationId() + "/check-in",
            stay.staffToken(), body);

        assertThat(checkedIn.getStatusCode().value()).as(checkedIn.getBody()).isEqualTo(200);
        assertThat(count("pre_arrival_guest", stay)).isZero();
        // the link stays (only the submissions go), so the guest's page answers "closed", not "invalid"
        assertThat(count("guest_link", stay)).isEqualTo(1);
        UUID stayGuest = jdbc.queryForObject(
            "SELECT id FROM hotel.stay_guest WHERE reservation_id = ?", UUID.class, stay.reservationId());
        assertThat(jdbc.queryForObject("SELECT stay_guest_id FROM hotel.id_scan WHERE id = ?", UUID.class, linkedScan))
            .isEqualTo(stayGuest);
        // a scan nobody used stays, unlinked, for the purge
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.id_scan WHERE id = ? AND stay_guest_id IS NULL", Integer.class, spareScan))
            .isEqualTo(1);
        assertThat(get(guests(stay), stay.staffToken()).getStatusCode().value()).isEqualTo(200);
        assertThat(data(get(guests(stay), stay.staffToken()))).isEmpty();
        ResponseEntity<String> page = pub(HttpMethod.GET, PUBLIC, stay.guestToken());
        assertThat(page.getStatusCode().value()).as(page.getBody()).isEqualTo(200);
        assertThat(data(page).path("open").asBoolean()).isFalse();
        assertThat(data(page).path("guests")).isEmpty();
    }

    @Test
    void cancel_clears_rows_and_scans_but_keeps_the_link() {
        Stay stay = stay(1, 3);
        UUID scan = scanOf(stay, CccdQrImages.payload());
        UUID unsubmittedScan = scanOf(stay, CccdQrImages.payload("079090777777", "TRAN THI BICH"));
        assertThat(submit(stay, CccdQrImages.ID_NUMBER, CccdQrImages.FULL_NAME, scan).getStatusCode().value())
            .isEqualTo(201);

        ResponseEntity<String> cancelled = post(
            HOTEL_API + "/reservations/" + stay.reservationId() + "/cancel", stay.staffToken(), null);

        assertThat(cancelled.getStatusCode().value()).as(cancelled.getBody()).isEqualTo(200);
        assertThat(count("pre_arrival_guest", stay)).isZero();
        assertThat(count("guest_link", stay)).isEqualTo(1);
        assertThat(count("id_scan", stay)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.id_scan WHERE id IN (?, ?)", Integer.class,
            scan, unsubmittedScan)).isZero();
        ResponseEntity<String> page = pub(HttpMethod.GET, PUBLIC, stay.guestToken());
        assertThat(page.getStatusCode().value()).as(page.getBody()).isEqualTo(200);
        assertThat(data(page).path("open").asBoolean()).isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String guests(Stay stay) {
        return HOTEL_API + "/reservations/" + stay.reservationId() + "/pre-arrival-guests";
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /** A confirmed one-adult reservation from {@code startOffset} days from today for two nights, with a live link. */
    private Stay stay(int startOffset, int maxGuests) {
        String staff = adminToken();
        UUID room = newRoom(staff, "PS" + tag(), newRoomType(staff, "PS" + tag(), maxGuests, 900_000, true), true);
        UUID reservation = book(staff, room, hotelToday().plusDays(startOffset), hotelToday().plusDays(startOffset + 2), 1);
        ResponseEntity<String> created = post(
            HOTEL_API + "/reservations/" + reservation + "/guest-link", staff, null);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        String url = data(created).path("url").asText();
        assertThat(url).startsWith(BASE);
        return new Stay(reservation, url.substring(BASE.length()), staff);
    }

    private ResponseEntity<String> pub(HttpMethod method, String path, String guestToken) {
        return pub(method, path, guestToken, null);
    }

    private ResponseEntity<String> pub(HttpMethod method, String path, String guestToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, guestToken);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> submit(Stay stay, String idNumber, String name, UUID scanId) {
        return submit(stay, idNumber, name, scanId, "CCCD");
    }

    private ResponseEntity<String> submit(Stay stay, String idNumber, String name, UUID scanId, String idType) {
        Map<String, Object> identity = new LinkedHashMap<>(adult(idType, idNumber, name));
        identity.put("idScanId", scanId);
        if (scanId != null) {
            identity.put("address", "12 Le Loi, Phu Hoi, Hue");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identity", identity);
        body.put("consent", true);
        return pub(HttpMethod.POST, PUBLIC + "/guests", stay.guestToken(), body);
    }

    private UUID scanOf(Stay stay, String payload) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(CccdQrImages.jpeg(payload)) {
            @Override
            public String getFilename() {
                return "cccd.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, stay.guestToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> uploaded = rest.postForEntity(
            PUBLIC + "/id-scans", new HttpEntity<>(form, headers), String.class);
        assertThat(uploaded.getStatusCode().value()).as(uploaded.getBody()).isEqualTo(201);
        return UUID.fromString(data(uploaded).path("scanId").asText());
    }

    private ResponseEntity<byte[]> image(String token, UUID reservationId, UUID guestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(HOTEL_API + "/reservations/" + reservationId + "/pre-arrival-guests/" + guestId
            + "/id-scan", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private int count(String table, Stay stay) {
        return jdbc.queryForObject("SELECT count(*) FROM hotel." + table + " WHERE reservation_id = ?",
            Integer.class, stay.reservationId());
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
}
