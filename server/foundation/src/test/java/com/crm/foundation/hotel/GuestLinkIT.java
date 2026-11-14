package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Personal check-in links: create, regenerate, revoke, status (staff endpoints) over HTTP. */
class GuestLinkIT extends HotelITSupport {

    private static final String BASE = "https://checkin.mathisterben.org/#";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void create_returns_url_with_fragment_token() {
        String token = adminToken();
        UUID id = booked(token, 2);

        ResponseEntity<String> response = post(link(id), token, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        String url = data(response).path("url").asText();
        assertThat(url).startsWith(BASE);
        assertThat(url.substring(BASE.length())).matches("[A-Za-z0-9_-]{43}");
        assertThat(data(response).path("expiresAt").asText()).isNotBlank();
        // only the SHA-256 of the token is stored
        assertThat(storedHash(id)).isEqualTo(sha256(url.substring(BASE.length())));
    }

    @Test
    void regenerate_kills_old_token() {
        String token = adminToken();
        UUID id = booked(token, 1);
        String first = tokenOf(post(link(id), token, null));
        byte[] firstHash = storedHash(id);
        assertThat(firstHash).isEqualTo(sha256(first));

        String second = tokenOf(post(link(id), token, null));

        assertThat(second).isNotEqualTo(first);
        assertThat(storedHash(id)).isEqualTo(sha256(second)).isNotEqualTo(firstHash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest_link WHERE token_hash = ?",
            Integer.class, (Object) firstHash)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest_link WHERE reservation_id = ?",
            Integer.class, id)).isEqualTo(1);
    }

    @Test
    void revoke_then_status_not_exists() {
        String token = adminToken();
        UUID id = booked(token, 1);
        assertThat(json(get(link(id), token)).path("data").path("exists").asBoolean()).isFalse();
        post(link(id), token, null);
        JsonNode existing = data(get(link(id), token));
        assertThat(existing.path("exists").asBoolean()).isTrue();
        assertThat(existing.path("createdAt").asText()).isNotBlank();
        assertThat(existing.path("expiresAt").asText()).isNotBlank();

        ResponseEntity<String> revoked = exchange(HttpMethod.DELETE, link(id), token, null);

        assertThat(revoked.getStatusCode().value()).isEqualTo(204);
        JsonNode gone = data(get(link(id), token));
        assertThat(gone.path("exists").asBoolean()).isFalse();
        assertThat(gone.path("expiresAt").isNull()).isTrue();
        assertThat(exchange(HttpMethod.DELETE, link(id), token, null).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest_link WHERE reservation_id = ?",
            Integer.class, id)).isZero();
    }

    @Test
    void expires_at_is_0600_day_after_checkout() {
        String token = adminToken();
        LocalDate in = hotelToday().plusDays(2);
        LocalDate out = in.plusDays(3);
        UUID id = book(token, newRoom(token, "GL" + tag(), newRoomType(token, "GL" + tag(), 3, 900_000, true), true),
            in, out, 1);

        ResponseEntity<String> response = post(link(id), token, null);

        Instant expected = out.plusDays(1).atTime(LocalTime.of(6, 0))
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(Instant.parse(data(response).path("expiresAt").asText())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM hotel.guest_link WHERE reservation_id = ?",
            Timestamp.class, id).toInstant()).isEqualTo(expected);
        assertThat(Instant.parse(data(get(link(id), token)).path("expiresAt").asText())).isEqualTo(expected);
    }

    @Test
    void only_confirmed_reservation_gets_link() {
        String token = adminToken();
        UUID checkedIn = booked(token, 1);
        UUID cancelled = booked(token, 1);
        jdbc.update("UPDATE hotel.reservation SET status = 'CHECKED_IN' WHERE id = ?", checkedIn);
        jdbc.update("UPDATE hotel.reservation SET status = 'CANCELLED' WHERE id = ?", cancelled);

        ResponseEntity<String> in = post(link(checkedIn), token, null);
        ResponseEntity<String> out = post(link(cancelled), token, null);
        ResponseEntity<String> unknown = post(link(UUID.randomUUID()), token, null);

        assertThat(in.getStatusCode().value()).as(in.getBody()).isEqualTo(409);
        assertThat(json(in).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(out.getStatusCode().value()).isEqualTo(409);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest_link WHERE reservation_id IN (?, ?)",
            Integer.class, checkedIn, cancelled)).isZero();
    }

    @Test
    void past_checkout_cannot_get_link_409() {
        String token = adminToken();
        UUID room = newRoom(token, "GL" + tag(), newRoomType(token, "GL" + tag(), 3, 900_000, true), true);
        // Confirmed but long over: its link would already be expired
        UUID id = seedReservation(room, hotelToday().minusDays(10), hotelToday().minusDays(8), "CONFIRMED");

        ResponseEntity<String> response = post(link(id), token, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_CHECKIN_CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest_link WHERE reservation_id = ?",
            Integer.class, id)).isZero();
    }

    @Test
    void audit_has_no_token_or_url() {
        String token = adminToken();
        UUID id = booked(token, 1);
        String guestToken = tokenOf(post(link(id), token, null));
        exchange(HttpMethod.DELETE, link(id), token, null);

        List<String> events = jdbc.queryForList("""
            SELECT op || '|' || entity_type || '|' || coalesce(before_json::text, '') || '|'
                   || coalesce(after_json::text, '') FROM audit_event
            WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op LIKE 'GUEST_LINK_%' ORDER BY op
            """, String.class, id);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).startsWith("GUEST_LINK_CREATE|Reservation||").contains("expiresAt");
        assertThat(events.get(1)).isEqualTo("GUEST_LINK_REVOKE|Reservation||");
        assertThat(String.join(" ", events)).doesNotContain(guestToken).doesNotContain("checkin.mathisterben")
            .doesNotContain("http");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event WHERE entity_id = ? AND (after_json::text LIKE ? OR before_json::text LIKE ?)
            """, Integer.class, id, "%" + guestToken + "%", "%" + guestToken + "%")).isZero();
    }

    @Test
    void stays_checkin_permission_required_403() {
        String admin = adminToken();
        UUID id = booked(admin, 1);
        String reader = tokenWith("hotel_it_link_reader", "hotel.reservations.read");
        String checkinOnly = tokenWith("hotel_it_link_checkin", "hotel.stays.checkin");

        assertThat(post(link(id), reader, null).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange(HttpMethod.DELETE, link(id), reader, null).getStatusCode().value()).isEqualTo(403);
        assertThat(get(link(id), reader).getStatusCode().value()).isEqualTo(200);
        assertThat(get(link(id), checkinOnly).getStatusCode().value()).isEqualTo(403);
        assertThat(post(link(id), null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest_link WHERE reservation_id = ?",
            Integer.class, id)).isZero();
        assertThat(post(link(id), checkinOnly, null).getStatusCode().value()).isEqualTo(201);
    }

    private static String link(UUID reservationId) {
        return HOTEL_API + "/reservations/" + reservationId + "/guest-link";
    }

    private String tokenOf(ResponseEntity<String> created) {
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        String url = data(created).path("url").asText();
        assertThat(url).startsWith(BASE);
        return url.substring(BASE.length());
    }

    private byte[] storedHash(UUID reservationId) {
        return jdbc.queryForObject("SELECT token_hash FROM hotel.guest_link WHERE reservation_id = ?",
            byte[].class, reservationId);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A confirmed reservation of {@code adults} for tomorrow. */
    private UUID booked(String token, int adults) {
        UUID room = newRoom(token, "GL" + tag(), newRoomType(token, "GL" + tag(), 3, 900_000, true), true);
        return book(token, room, hotelToday().plusDays(1), hotelToday().plusDays(3), adults);
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
