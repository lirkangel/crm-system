package com.crm.foundation.hotel;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.config.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class of the hotel ITs: boots the full application on a random port
 * against Testcontainers Postgres with the real packaged hotel ZIP (copied to
 * {@code target/test-plugins} by the build) as the only installed plugin.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
public abstract class HotelITSupport {

    protected static final String HOTEL_PLUGIN_ID = "com.crm.hotel";
    protected static final String HOTEL_SCHEMA = "hotel";
    protected static final String HOTEL_API = "/api/v1/plugins/hotel";

    private static final String ADMIN_USERNAME = "hotel_it_admin";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    /** Points the plugin host at the directory holding only the packaged hotel ZIP. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> hotelPluginDirectory().toString());
    }

    /**
     * Absolute {@code <module>/target/test-plugins}, derived from this class's
     * own location ({@code target/test-classes}): under {@code forkCount=0} the
     * JVM's working directory is the reactor root, so a relative path is wrong.
     */
    static Path hotelPluginDirectory() {
        try {
            Path testClasses = Path.of(HotelITSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = testClasses.getParent().resolve("test-plugins").toAbsolutePath();
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("only the packaged hotel plugin belongs in " + dir)
                    .singleElement().asString().isEqualTo("hotel-plugin.zip");
            }
            return dir;
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("hotel plugin directory target/test-plugins is not usable", e);
        }
    }

    /** Access token of an {@code admin} user (created on first use), obtained by logging in over HTTP. */
    protected String adminToken() {
        if (userRepository.findByUsername(ADMIN_USERNAME).isEmpty()) {
            User admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setEmail(ADMIN_USERNAME + "@example.com");
            // Same hashing as UserServiceImpl (static BCrypt; there is no PasswordEncoder bean)
            admin.setPassword(BCrypt.hashpw(ADMIN_PASSWORD, BCrypt.gensalt()));
            admin.getRoles().add(roleRepository.findByCode("admin").orElseThrow());
            userRepository.save(admin);
        }
        ResponseEntity<String> login = rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), String.class);
        assertThat(login.getStatusCode().value()).as(login.getBody()).isEqualTo(200);
        return data(login).path("accessToken").asText();
    }

    /**
     * Inserts a guest and a reservation of {@code roomId} over {@code [in, out)} with plain SQL, so
     * availability tests don't depend on the booking endpoint. {@code status} is {@code CONFIRMED}
     * or {@code CANCELLED}; returns the reservation id. (Two CONFIRMED stays of one room that
     * overlap are rejected by the database, as in production.)
     */
    protected UUID seedReservation(UUID roomId, LocalDate in, LocalDate out, String status) {
        UUID guestId = UUID.randomUUID();
        String name = "Seed Guest " + guestId.toString().substring(0, 8);
        jdbc.update(
            "INSERT INTO hotel.guest (id, full_name, phone, search_key, version) VALUES (?, ?, ?, ?, 0)",
            guestId, name, "0900000000", name.toLowerCase() + " 0900000000");
        UUID id = UUID.randomUUID();
        long nights = ChronoUnit.DAYS.between(in, out);
        long rate = 1_000_000L;
        jdbc.update("""
            INSERT INTO hotel.reservation (id, code, guest_id, room_id, check_in, check_out, adults, status,
                                           nightly_rate_gross_vnd, total_gross_vnd, created_at, version)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, now(), 0)
            """,
            id, "SEED-" + id.toString().replace("-", "").substring(0, 12), guestId, roomId, in, out, status,
            rate, rate * nights);
        return id;
    }

    /** Creates a room type over HTTP (asserting 201) and returns its id. */
    protected UUID newRoomType(String token, String code, int maxGuests, long rate, boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("nameI18n", Map.of("vi", "Phòng " + code, "en", "Room " + code));
        body.put("maxGuests", maxGuests);
        body.put("nightlyRateGrossVnd", rate);
        body.put("active", active);
        ResponseEntity<String> created = post(HOTEL_API + "/room-types", token, body);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    /** Creates a room over HTTP (asserting 201) and returns its id. */
    protected UUID newRoom(String token, String number, UUID roomTypeId, boolean inService) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", number);
        body.put("roomTypeId", roomTypeId);
        body.put("floor", 1);
        body.put("inService", inService);
        ResponseEntity<String> created = post(HOTEL_API + "/rooms", token, body);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    /** Body of {@code POST /reservations}; pass exactly one of {@code guestId} / {@code newGuest} for a valid one. */
    protected static Map<String, Object> reservationBody(
            UUID guestId, Map<String, Object> newGuest, UUID roomId, LocalDate in, LocalDate out, int adults) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("guestId", guestId);
        body.put("newGuest", newGuest);
        body.put("roomId", roomId);
        body.put("checkIn", in);
        body.put("checkOut", out);
        body.put("adults", adults);
        body.put("notes", "Late arrival");
        return body;
    }

    /** An inline new guest for {@link #reservationBody}. */
    protected static Map<String, Object> inlineGuest(String fullName, String phone) {
        Map<String, Object> guest = new LinkedHashMap<>();
        guest.put("fullName", fullName);
        guest.put("phone", phone);
        guest.put("email", null);
        return guest;
    }

    protected ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected ResponseEntity<String> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ResponseEntity<String> put(String path, String token, Object body, String ifMatch) {
        return exchange(HttpMethod.PUT, path, token, body, "If-Match", ifMatch);
    }

    protected ResponseEntity<String> exchange(
            HttpMethod method, String path, String token, Object body, String... extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            headers.set(extraHeaders[i], extraHeaders[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** Whole response body parsed as JSON. */
    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (IOException e) {
            throw new IllegalStateException("Unparseable response: " + response.getBody(), e);
        }
    }

    /** The {@code data} member of the {@code CommonResponse} envelope. */
    protected JsonNode data(ResponseEntity<String> response) {
        return json(response).path("data");
    }
}
