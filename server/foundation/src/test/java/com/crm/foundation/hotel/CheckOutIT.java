package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Check-out over HTTP: zero-balance rule, early check-out, read-only folio afterwards, audit and sync; and its
 * same-day undo.
 */
class CheckOutIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";
    /** Three nights at 1,000,000. */
    private static final long ROOM_CHARGE = 3_000_000L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void check_out_at_zero_balance() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        pay(token, stay.id(), "OTA", "BOOKING_COM", "BK-1", ROOM_CHARGE - 100_000);
        charge(token, stay.id(), "Minibar", 50_000);
        pay(token, stay.id(), "CASH", null, null, 150_000);
        long versionBefore = jdbc.queryForObject(
            "SELECT version FROM hotel.reservation WHERE id = ?", Long.class, stay.id());

        ResponseEntity<String> response = checkOut(token, stay.id());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode view = data(response);
        assertThat(view.path("id").asText()).isEqualTo(stay.id().toString());
        assertThat(view.path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(view.path("checkedOutAt").isNull()).isFalse();
        assertThat(view.path("checkedInAt").isNull()).isFalse();
        assertThat(view.path("version").asLong()).isEqualTo(versionBefore + 1);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, stay.id())).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.queryForObject(
            "SELECT checked_out_at IS NOT NULL FROM hotel.reservation WHERE id = ?", Boolean.class, stay.id()))
            .isTrue();
        // the room is bookable again right after
        LocalDate today = hotelToday();
        assertThat(post(HOTEL_API + "/reservations", token, reservationBody(
            null, inlineGuest("Next " + tag(), "0911111111"), stay.roomId(), today, today.plusDays(1), 1))
            .getStatusCode().value()).isEqualTo(201);

        // change feed: the UPDATE of the reservation at the new version
        List<String> feed = jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? AND version = ?",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, stay.id(), versionBefore + 1);
        assertThat(feed).containsExactly("Reservation:UPDATE:" + (versionBefore + 1));

        // audit: before CHECKED_IN, after CHECKED_OUT, no identity data
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT entity_type, before_json::text AS before, after_json::text AS after
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_OUT'
            """, stay.id());
        assertThat(row.get("entity_type")).isEqualTo("Reservation");
        JsonNode auditBefore = readJson((String) row.get("before"));
        JsonNode auditAfter = readJson((String) row.get("after"));
        assertThat(auditBefore.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(auditBefore.path("checkedOutAt").isNull()).isTrue();
        assertThat(auditAfter.path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(auditAfter.path("checkedOutAt").isNull()).isFalse();
        assertThat((String) row.get("before") + row.get("after"))
            .doesNotContain(stay.idNumber()).doesNotContain(stay.idName());
    }

    @Test
    void balance_due_blocks_check_out() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        pay(token, stay.id(), "CASH", null, null, ROOM_CHARGE - 1_234_000);

        ResponseEntity<String> response = checkOut(token, stay.id());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        JsonNode problem = json(response);
        assertThat(problem.path("code").asText()).isEqualTo("HOTEL_BALANCE_DUE");
        assertThat(problem.path("detail").asText()).isEqualTo("balance due: 1234000 VND");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, stay.id())).isEqualTo("CHECKED_IN");
        assertThat(jdbc.queryForObject(
            "SELECT checked_out_at IS NULL FROM hotel.reservation WHERE id = ?", Boolean.class, stay.id())).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_OUT'",
            Integer.class, stay.id())).isZero();
    }

    @Test
    void early_check_out_frees_future_nights() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        LocalDate today = hotelToday();
        pay(token, stay.id(), "CASH", null, null, ROOM_CHARGE);
        ResponseEntity<String> whileStaying = post(HOTEL_API + "/reservations", token, reservationBody(
            null, inlineGuest("Blocked " + tag(), "0922222222"), stay.roomId(), today.plusDays(1), today.plusDays(3), 1));

        ResponseEntity<String> out = checkOut(token, stay.id());
        ResponseEntity<String> afterwards = post(HOTEL_API + "/reservations", token, reservationBody(
            null, inlineGuest("Next " + tag(), "0933333333"), stay.roomId(), today.plusDays(1), today.plusDays(3), 1));

        assertThat(whileStaying.getStatusCode().value()).as(whileStaying.getBody()).isEqualTo(409);
        assertThat(json(whileStaying).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        assertThat(out.getStatusCode().value()).as(out.getBody()).isEqualTo(200);
        assertThat(afterwards.getStatusCode().value()).as(afterwards.getBody()).isEqualTo(201);
        // the folio keeps the full booked room charge
        JsonNode folio = data(get(RESERVATIONS + "/" + stay.id() + "/folio", token));
        assertThat(folio.path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(folio.path("roomChargeGrossVnd").asLong()).isEqualTo(ROOM_CHARGE);
        assertThat(folio.path("balanceGrossVnd").asLong()).isZero();
    }

    @Test
    void folio_read_only_after_check_out() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        ResponseEntity<String> chargeResponse = charge(token, stay.id(), "Minibar", 20_000);
        UUID chargeId = UUID.fromString(data(chargeResponse).path("charges").get(0).path("id").asText());
        ResponseEntity<String> paid = pay(token, stay.id(), "CASH", null, null, ROOM_CHARGE + 20_000);
        UUID paymentId = UUID.fromString(data(paid).path("payments").get(0).path("id").asText());
        assertThat(checkOut(token, stay.id()).getStatusCode().value()).isEqualTo(200);

        List<ResponseEntity<String>> writes = List.of(
            charge(token, stay.id(), "Late snack", 10_000),
            pay(token, stay.id(), "CASH", null, null, 10_000),
            post(RESERVATIONS + "/" + stay.id() + "/folio/charges/" + chargeId + "/void", token, Map.of("reason", "oops")),
            post(RESERVATIONS + "/" + stay.id() + "/folio/payments/" + paymentId + "/void", token, Map.of("reason", "oops")));

        for (ResponseEntity<String> write : writes) {
            assertThat(write.getStatusCode().value()).as(write.getBody()).isEqualTo(409);
            assertThat(json(write).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        }
        JsonNode folio = data(get(RESERVATIONS + "/" + stay.id() + "/folio", token));
        assertThat(folio.path("charges")).hasSize(1);
        assertThat(folio.path("payments")).hasSize(1);
        assertThat(folio.path("charges").get(0).path("voidedAt").isNull()).isTrue();
        assertThat(folio.path("payments").get(0).path("voidedAt").isNull()).isTrue();
        assertThat(folio.path("balanceGrossVnd").asLong()).isZero();
    }

    @Test
    void only_checked_in_can_check_out() {
        String token = adminToken();
        String tag = tag();
        UUID room = newRoom(token, "CO" + tag, newRoomType(token, "CO" + tag, 2, 1_000_000, true), true);
        LocalDate today = hotelToday();
        UUID confirmed = book(token, room, today, today.plusDays(1), 1);
        UUID cancelled = book(token, room, today.plusDays(1), today.plusDays(2), 1);
        assertThat(post(RESERVATIONS + "/" + cancelled + "/cancel", token, null).getStatusCode().value()).isEqualTo(200);
        Stay done = checkedInStay(token);
        pay(token, done.id(), "CASH", null, null, ROOM_CHARGE);
        assertThat(checkOut(token, done.id()).getStatusCode().value()).isEqualTo(200);

        for (UUID id : List.of(confirmed, cancelled, done.id())) {
            ResponseEntity<String> response = checkOut(token, id);
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        }
        ResponseEntity<String> unknown = checkOut(token, UUID.randomUUID());
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, confirmed)).isEqualTo("CONFIRMED");
    }

    @Test
    void check_out_permission_403() {
        String admin = adminToken();
        Stay stay = checkedInStay(admin);
        pay(admin, stay.id(), "CASH", null, null, ROOM_CHARGE);
        String checkInOnly = tokenWith("hotel_it_checkout_none", "hotel.stays.checkin", "hotel.folio.pay", "hotel.reservations.read");
        String checker = tokenWith("hotel_it_checkout_ok", "hotel.stays.checkout");

        assertThat(checkOut(checkInOnly, stay.id()).getStatusCode().value()).isEqualTo(403);
        assertThat(checkOut(null, stay.id()).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, stay.id())).isEqualTo("CHECKED_IN");
        assertThat(checkOut(checker, stay.id()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void undo_check_out_same_day() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        pay(token, stay.id(), "CASH", null, null, ROOM_CHARGE);
        JsonNode checkedOut = data(checkOut(token, stay.id()));
        long versionBefore = checkedOut.path("version").asLong();

        ResponseEntity<String> response = undoCheckOut(token, stay.id());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode view = data(response);
        assertThat(view.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(view.path("checkedOutAt").isNull()).isTrue();
        assertThat(view.path("checkedInAt").asText()).isEqualTo(checkedOut.path("checkedInAt").asText());
        assertThat(view.path("roomId").asText()).isEqualTo(stay.roomId().toString());
        assertThat(view.path("version").asLong()).isEqualTo(versionBefore + 1);
        assertThat(jdbc.queryForObject(
            "SELECT checked_out_at IS NULL FROM hotel.reservation WHERE id = ?", Boolean.class, stay.id())).isTrue();
        // the identity records are still there and the folio takes charges again
        assertThat(data(get(RESERVATIONS + "/" + stay.id() + "/stay-guests", token))).hasSize(1);
        ResponseEntity<String> lateCharge = charge(token, stay.id(), "Late snack", 30_000);
        assertThat(lateCharge.getStatusCode().value()).as(lateCharge.getBody()).isEqualTo(201);
        // the room is blocked again for the rest of the stay
        LocalDate today = hotelToday();
        ResponseEntity<String> rebook = post(HOTEL_API + "/reservations", token, reservationBody(
            null, inlineGuest("Blocked " + tag(), "0944444444"), stay.roomId(), today.plusDays(1), today.plusDays(2), 1));
        assertThat(rebook.getStatusCode().value()).as(rebook.getBody()).isEqualTo(409);
        assertThat(json(rebook).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");

        List<String> feed = jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? AND version = ?",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, stay.id(), versionBefore + 1);
        assertThat(feed).containsExactly("Reservation:UPDATE:" + (versionBefore + 1));

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT entity_type, before_json::text AS before, after_json::text AS after
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_OUT_UNDO'
            """, stay.id());
        assertThat(row.get("entity_type")).isEqualTo("Reservation");
        JsonNode auditBefore = readJson((String) row.get("before"));
        JsonNode auditAfter = readJson((String) row.get("after"));
        assertThat(auditBefore.path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(auditBefore.path("checkedOutAt").isNull()).isFalse();
        assertThat(auditAfter.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(auditAfter.path("checkedOutAt").isNull()).isTrue();
        assertThat((String) row.get("before") + row.get("after"))
            .doesNotContain(stay.idNumber()).doesNotContain(stay.idName());
    }

    @Test
    void undo_check_out_only_on_the_business_date_of_check_out() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        pay(token, stay.id(), "CASH", null, null, ROOM_CHARGE);
        assertThat(checkOut(token, stay.id()).getStatusCode().value()).isEqualTo(200);
        // noon of the previous business date
        Instant yesterday = hotelToday().minusDays(1).atTime(12, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        jdbc.update("UPDATE hotel.reservation SET checked_out_at = ? WHERE id = ?",
            java.sql.Timestamp.from(yesterday), stay.id());

        ResponseEntity<String> response = undoCheckOut(token, stay.id());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, stay.id())).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_OUT_UNDO'",
            Integer.class, stay.id())).isZero();
    }

    @Test
    void only_checked_out_can_be_undone() {
        String token = adminToken();
        String tag = tag();
        UUID room = newRoom(token, "UO" + tag, newRoomType(token, "UO" + tag, 2, 1_000_000, true), true);
        LocalDate today = hotelToday();
        UUID confirmed = book(token, room, today, today.plusDays(1), 1);
        UUID cancelled = book(token, room, today.plusDays(1), today.plusDays(2), 1);
        assertThat(post(RESERVATIONS + "/" + cancelled + "/cancel", token, null).getStatusCode().value()).isEqualTo(200);
        Stay checkedIn = checkedInStay(token);

        for (UUID id : List.of(confirmed, cancelled, checkedIn.id())) {
            ResponseEntity<String> response = undoCheckOut(token, id);
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        }
        ResponseEntity<String> unknown = undoCheckOut(token, UUID.randomUUID());
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, checkedIn.id())).isEqualTo("CHECKED_IN");
    }

    @Test
    void undo_check_out_refused_while_another_stay_occupies_the_room() {
        String token = adminToken();
        String tag = tag();
        UUID room = newRoom(token, "UO" + tag, newRoomType(token, "UO" + tag, 2, 1_000_000, true), true);
        LocalDate today = hotelToday();
        // last night's stay, checked out this morning; its nights don't overlap the next guest's
        UUID left = seedReservation(room, today.minusDays(1), today, "CHECKED_OUT");
        jdbc.update("UPDATE hotel.reservation SET checked_in_at = now() - interval '1 hour', checked_out_at = now() "
            + "WHERE id = ?", left);
        UUID next = book(token, room, today, today.plusDays(1), 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adults", List.of(adult("CCCD", "079099998888", "Next Guest " + tag)));
        body.put("roomId", null);
        assertThat(post(RESERVATIONS + "/" + next + "/check-in", token, body).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> response = undoCheckOut(token, left);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_OCCUPIED");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, left)).isEqualTo("CHECKED_OUT");
    }

    @Test
    void undo_check_out_refused_once_the_freed_nights_are_rebooked() {
        String token = adminToken();
        Stay stay = checkedInStay(token);
        pay(token, stay.id(), "CASH", null, null, ROOM_CHARGE);
        assertThat(checkOut(token, stay.id()).getStatusCode().value()).isEqualTo(200);
        LocalDate today = hotelToday();
        book(token, stay.roomId(), today.plusDays(1), today.plusDays(3), 1);

        ResponseEntity<String> response = undoCheckOut(token, stay.id());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, stay.id())).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.queryForObject(
            "SELECT checked_out_at IS NOT NULL FROM hotel.reservation WHERE id = ?", Boolean.class, stay.id())).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_OUT_UNDO'",
            Integer.class, stay.id())).isZero();
    }

    @Test
    void undo_check_out_permission_403() {
        String admin = adminToken();
        Stay stay = checkedInStay(admin);
        pay(admin, stay.id(), "CASH", null, null, ROOM_CHARGE);
        assertThat(checkOut(admin, stay.id()).getStatusCode().value()).isEqualTo(200);
        String checkInOnly = tokenWith("hotel_it_undo_checkout_none", "hotel.stays.checkin", "hotel.reservations.read");
        String checker = tokenWith("hotel_it_undo_checkout_ok", "hotel.stays.checkout");

        assertThat(undoCheckOut(checkInOnly, stay.id()).getStatusCode().value()).isEqualTo(403);
        assertThat(undoCheckOut(null, stay.id()).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM hotel.reservation WHERE id = ?", String.class, stay.id())).isEqualTo("CHECKED_OUT");
        assertThat(undoCheckOut(checker, stay.id()).getStatusCode().value()).isEqualTo(200);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private ResponseEntity<String> undoCheckOut(String token, UUID id) {
        return post(RESERVATIONS + "/" + id + "/undo-check-out", token, null);
    }

    private record Stay(UUID id, UUID roomId, String idNumber, String idName) {
    }

    private JsonNode readJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> checkOut(String token, UUID id) {
        return post(RESERVATIONS + "/" + id + "/check-out", token, null);
    }

    /** A checked-in stay of three nights (today..today+3, 1,000,000 a night) in a fresh room. */
    private Stay checkedInStay(String token) {
        String tag = tag();
        UUID room = newRoom(token, "CO" + tag, newRoomType(token, "CO" + tag, 2, 1_000_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(3), 1);
        String idNumber = "0790" + (10_000_000 + (long) (Math.random() * 89_000_000));
        String idName = "Checkout Guest " + tag;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adults", List.of(adult("CCCD", idNumber, idName)));
        body.put("roomId", null);
        ResponseEntity<String> response = post(RESERVATIONS + "/" + id + "/check-in", token, body);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return new Stay(id, room, idNumber, idName);
    }

    private ResponseEntity<String> charge(String token, UUID id, String description, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        body.put("amountGrossVnd", amount);
        return post(RESERVATIONS + "/" + id + "/folio/charges", token, body);
    }

    private ResponseEntity<String> pay(
            String token, UUID id, String method, String otaChannel, String reference, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("otaChannel", otaChannel);
        body.put("reference", reference);
        body.put("amountGrossVnd", amount);
        return post(RESERVATIONS + "/" + id + "/folio/payments", token, body);
    }

    /** Access token of a persisted non-admin user holding only {@code permissions}. */
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

    /** Six hex characters: short enough for the 16-character room number column. */
    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
