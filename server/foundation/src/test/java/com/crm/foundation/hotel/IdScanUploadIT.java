package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** CCCD scan upload ({@code POST /reservations/{id}/id-scans}) over HTTP. */
class IdScanUploadIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void upload_returns_fields_and_stores_downscaled_jpeg() throws IOException {
        String token = adminToken();
        UUID id = confirmed(token);
        byte[] original = CccdQrImages.jpeg(CccdQrImages.payload());

        ResponseEntity<String> response = upload(token, id, original);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        JsonNode data = data(response);
        UUID scanId = UUID.fromString(data.path("scanId").asText());
        JsonNode fields = data.path("fields");
        assertThat(fields.path("idType").asText()).isEqualTo("CCCD");
        assertThat(fields.path("idNumber").asText()).isEqualTo(CccdQrImages.ID_NUMBER);
        assertThat(fields.path("fullName").asText()).isEqualTo(CccdQrImages.FULL_NAME);
        assertThat(fields.path("dateOfBirth").asText()).isEqualTo("1990-05-17");
        assertThat(fields.path("nationality").asText()).isEqualTo("VN");
        assertThat(fields.path("address").asText()).isEqualTo("12 Le Loi, Phu Hoi, Hue");
        assertThat(fields.path("sex").asText()).isEqualTo("Nam");
        assertThat(fields.path("issueDate").asText()).isEqualTo("2021-06-10");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT reservation_id, stay_guest_id, content_type, image, size_bytes, uploaded_by, uploaded_at, version "
                + "FROM hotel.id_scan WHERE id = ?", scanId);
        assertThat(row.get("reservation_id")).isEqualTo(id);
        assertThat(row.get("stay_guest_id")).isNull();
        assertThat(row.get("content_type")).isEqualTo("image/jpeg");
        assertThat(row.get("uploaded_by")).isNotNull();
        assertThat(row.get("uploaded_at")).isNotNull();
        byte[] stored = (byte[]) row.get("image");
        assertThat(((Number) row.get("size_bytes")).intValue()).isEqualTo(stored.length);
        assertThat(stored.length).isLessThan(original.length);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(1600);
        assertThat(stored[0] & 0xFF).isEqualTo(0xFF);
        assertThat(stored[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void png_upload_is_accepted() {
        String token = adminToken();
        UUID id = confirmed(token);

        ResponseEntity<String> response = upload(token, id, CccdQrImages.png(CccdQrImages.payload()));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(scanCount(id)).isEqualTo(1);
    }

    @Test
    void front_side_or_foreign_qr_is_unreadable_and_not_stored() {
        String token = adminToken();
        UUID id = confirmed(token);
        List<byte[]> unreadable = List.of(
            CccdQrImages.jpegWithoutQr(1),
            CccdQrImages.jpeg("https://example.com/not-a-cccd", 2),
            CccdQrImages.jpeg("07909012345|x|NGUYEN|17051990|Nam|Hue|10062021", 3),
            "JPEG-less garbage".getBytes(),
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3});

        for (int i = 0; i < unreadable.size(); i++) {
            ResponseEntity<String> response = upload(token, id, unreadable.get(i));
            if (i == 3) {
                assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(415);
            } else {
                assertThat(response.getStatusCode().value()).as("case " + i + ": " + response.getBody()).isEqualTo(400);
                assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_SCAN_UNREADABLE");
            }
        }
        assertThat(scanCount(id)).isZero();
    }

    @Test
    void heic_is_415_and_over_10mb_is_413() {
        String token = adminToken();
        UUID id = confirmed(token);
        byte[] heic = new byte[64];
        System.arraycopy(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, 0, heic, 0, 12);

        ResponseEntity<String> unsupported = upload(token, id, heic);
        byte[] huge = new byte[10 * 1024 * 1024 + 1024];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        HttpResponse<String> tooLarge = uploadRaw(token, id, huge);

        assertThat(unsupported.getStatusCode().value()).as(unsupported.getBody()).isEqualTo(415);
        assertThat(json(unsupported).path("code").asText()).isEqualTo("HOTEL_SCAN_UNSUPPORTED");
        // Foundation's multipart limit answers before the plugin does
        assertThat(tooLarge.statusCode()).as(tooLarge.body()).isEqualTo(413);
        assertThat(readJson(tooLarge.body()).path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(scanCount(id)).isZero();
    }

    @Test
    void duplicate_upload_returns_same_scan() {
        String token = adminToken();
        UUID id = confirmed(token);
        byte[] image = CccdQrImages.jpeg(CccdQrImages.payload());

        ResponseEntity<String> first = upload(token, id, image);
        ResponseEntity<String> second = upload(token, id, image);

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(200);
        assertThat(data(second).path("scanId").asText()).isEqualTo(data(first).path("scanId").asText());
        assertThat(data(second).path("fields").path("idNumber").asText()).isEqualTo(CccdQrImages.ID_NUMBER);
        assertThat(second.getHeaders().getCacheControl()).contains("no-store");
        assertThat(scanCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND op = 'ID_SCAN_UPLOAD' "
                + "AND entity_id = ?", Integer.class, UUID.fromString(data(first).path("scanId").asText()))).isEqualTo(1);
    }

    @Test
    void twenty_first_scan_is_409_limit() {
        String token = adminToken();
        UUID id = confirmed(token);

        for (int i = 0; i < 20; i++) {
            ResponseEntity<String> ok = upload(token, id, CccdQrImages.jpeg(CccdQrImages.payload(), i));
            assertThat(ok.getStatusCode().value()).as("scan " + i + ": " + ok.getBody()).isEqualTo(201);
        }
        ResponseEntity<String> over = upload(token, id, CccdQrImages.jpeg(CccdQrImages.payload(), 20));

        assertThat(over.getStatusCode().value()).as(over.getBody()).isEqualTo(409);
        assertThat(json(over).path("code").asText()).isEqualTo("HOTEL_SCAN_LIMIT");
        assertThat(scanCount(id)).isEqualTo(20);
    }

    @Test
    void cancelled_or_checked_out_reservation_is_409() {
        String token = adminToken();
        UUID room = newRoom(token, "SC" + tag(), newRoomType(token, "SC" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID cancelled = seedReservation(room, today, today.plusDays(1), "CANCELLED");
        UUID checkedOut = seedReservation(room, today.minusDays(5), today.minusDays(3), "CHECKED_OUT");
        byte[] image = CccdQrImages.jpeg(CccdQrImages.payload());

        for (UUID id : List.of(cancelled, checkedOut)) {
            ResponseEntity<String> response = upload(token, id, image);
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
            assertThat(scanCount(id)).isZero();
        }
        ResponseEntity<String> unknown = upload(token, UUID.randomUUID(), image);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void requires_stays_checkin_403() {
        String admin = adminToken();
        UUID id = confirmed(admin);
        String reader = tokenWith("hotel_it_scan_reader", "hotel.stays.read", "hotel.reservations.read");

        ResponseEntity<String> response = upload(reader, id, CccdQrImages.jpeg(CccdQrImages.payload()));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(403);
        assertThat(scanCount(id)).isZero();
    }

    @Test
    void response_is_no_store() {
        String token = adminToken();
        UUID id = confirmed(token);

        ResponseEntity<String> response = upload(token, id, CccdQrImages.jpeg(CccdQrImages.payload()));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void audit_has_size_only() {
        String token = adminToken();
        UUID id = confirmed(token);

        ResponseEntity<String> response = upload(token, id, CccdQrImages.jpeg(CccdQrImages.payload()));

        UUID scanId = UUID.fromString(data(response).path("scanId").asText());
        String after = jdbc.queryForObject(
            "SELECT after_json::text FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND op = 'ID_SCAN_UPLOAD' "
                + "AND entity_type = 'IdScan' AND entity_id = ?", String.class, scanId);
        int size = jdbc.queryForObject("SELECT size_bytes FROM hotel.id_scan WHERE id = ?", Integer.class, scanId);
        assertThat(readJson(after).path("sizeBytes").asInt()).isEqualTo(size);
        assertThat(after).doesNotContain(CccdQrImages.ID_NUMBER).doesNotContain("NGUYEN").doesNotContain("Le Loi");
        assertThat(readJson(after).size()).isEqualTo(1);
    }

    @Test
    void parallel_uploads_of_the_same_image_store_one_scan() throws Exception {
        String token = adminToken();
        UUID id = confirmed(token);
        byte[] image = CccdQrImages.jpeg(CccdQrImages.payload());

        // build every request body first, then release all threads together
        List<HttpEntity<MultiValueMap<String, Object>>> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(multipart(token, image));
        }
        CyclicBarrier start = new CyclicBarrier(requests.size());
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (HttpEntity<MultiValueMap<String, Object>> request : requests) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return send(id, request);
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> call : calls) {
                statuses.add(call.get().getStatusCode().value());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 200, 200, 200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scanCount(id)).isEqualTo(1);
        UUID scanId = jdbc.queryForObject("SELECT id FROM hotel.id_scan WHERE reservation_id = ?", UUID.class, id);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND op = 'ID_SCAN_UPLOAD' "
                + "AND entity_id = ?", Integer.class, scanId)).isEqualTo(1);
    }

    @Test
    void missing_image_part_is_400_and_stores_nothing() {
        String token = adminToken();
        UUID id = confirmed(token);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("other", "x");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.postForEntity(
            RESERVATIONS + "/" + id + "/id-scans", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(scanCount(id)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private UUID confirmed(String token) {
        UUID room = newRoom(token, "SC" + tag(), newRoomType(token, "SC" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        return book(token, room, today, today.plusDays(1), 1);
    }

    private ResponseEntity<String> upload(String token, UUID reservationId, byte[] bytes) {
        return send(reservationId, multipart(token, bytes));
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(String token, byte[] bytes) {
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
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> send(UUID reservationId, HttpEntity<MultiValueMap<String, Object>> request) {
        return rest.postForEntity(RESERVATIONS + "/" + reservationId + "/id-scans", request, String.class);
    }

    /**
     * A fixed-length multipart POST with the JDK client: the server answers 413 and drops the connection while the
     * body is still being sent, which TestRestTemplate's streaming request factory reports as an I/O error.
     */
    private HttpResponse<String> uploadRaw(String token, UUID reservationId, byte[] bytes) {
        String boundary = "----scan" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"cccd.jpg\"\r\n"
            + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + RESERVATIONS + "/" + reservationId + "/id-scans"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private int scanCount(UUID reservationId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM hotel.id_scan WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private JsonNode readJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(text, e);
        }
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
