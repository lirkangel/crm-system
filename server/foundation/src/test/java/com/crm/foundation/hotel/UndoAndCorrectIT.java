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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Same-day undo of a check-in and corrections of identity records, over HTTP. */
class UndoAndCorrectIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void undo_same_day_returns_to_confirmed_and_removes_identity_records() {
        String token = adminToken();
        UUID room = newRoom(token, "UN" + tag(), newRoomType(token, "UN" + tag(), 3, 1_000_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(2), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090100001", "Một"),
            adult("CCCD", "079090100002", "Hai"))).getStatusCode().value()).isEqualTo(200);
        List<UUID> stayGuests = jdbc.queryForList(
            "SELECT id FROM hotel.stay_guest WHERE reservation_id = ?", UUID.class, id);
        assertThat(stayGuests).hasSize(2);

        ResponseEntity<String> undone = post(RESERVATIONS + "/" + id + "/undo-check-in", token, null);

        assertThat(undone.getStatusCode().value()).as(undone.getBody()).isEqualTo(200);
        JsonNode view = data(undone);
        assertThat(view.path("id").asText()).isEqualTo(id.toString());
        assertThat(view.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(view.path("checkedInAt").isNull()).isTrue();
        assertThat(view.path("version").asLong()).isEqualTo(2L);
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
            "SELECT checked_in_at FROM hotel.reservation WHERE id = ?", Instant.class, id)).isNull();
        assertThat(stayGuestCount(id)).isZero();
        assertThat(data(get(RESERVATIONS + "/" + id + "/stay-guests", token))).isEmpty();
        // the room is still held by the confirmed stay
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.reservation WHERE room_id = ? AND status IN ('CONFIRMED','CHECKED_IN')",
            Integer.class, room)).isEqualTo(1);
        // change feed: the reservation update and one DELETE per identity record
        assertThat(changes(id)).containsExactly("Reservation:CREATE:0", "Reservation:UPDATE:1", "Reservation:UPDATE:2");
        for (UUID stayGuest : stayGuests) {
            assertThat(changes(stayGuest)).containsExactly("StayGuest:CREATE:0", "StayGuest:DELETE:1");
        }
        // audit: before/after of the reservation, no identity data
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT entity_type, user_id, before_json ->> 'status' AS before_status,
                   after_json ->> 'status' AS after_status, after_json::text AS after
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_IN_UNDO'
            """, id);
        assertThat(row.get("entity_type")).isEqualTo("Reservation");
        assertThat(row.get("user_id")).isNotNull();
        assertThat(row.get("before_status")).isEqualTo("CHECKED_IN");
        assertThat(row.get("after_status")).isEqualTo("CONFIRMED");
        assertThat((String) row.get("after")).doesNotContain("079090100001").doesNotContain("Một");
    }

    /** Review focus 5: an undone stay checks in again, and the stale identity records don't linger. */
    @Test
    void undo_then_check_in_again() {
        String token = adminToken();
        UUID room = newRoom(token, "UA" + tag(), newRoomType(token, "UA" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090200001", "Sai Tên"))).getStatusCode().value())
            .isEqualTo(200);
        assertThat(post(RESERVATIONS + "/" + id + "/undo-check-in", token, null).getStatusCode().value())
            .isEqualTo(200);

        ResponseEntity<String> again = checkIn(token, id, List.of(adult("CCCD", "079090200002", "Đúng Tên")));

        assertThat(again.getStatusCode().value()).as(again.getBody()).isEqualTo(200);
        assertThat(data(again).path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(data(again).path("version").asLong()).isEqualTo(3L);
        JsonNode guests = data(get(RESERVATIONS + "/" + id + "/stay-guests", token));
        assertThat(guests).hasSize(1);
        assertThat(guests.get(0).path("idNumber").asText()).isEqualTo("079090200002");
        // and the room is not double-held
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.reservation WHERE room_id = ? AND status IN ('CONFIRMED','CHECKED_IN')",
            Integer.class, room)).isEqualTo(1);
    }

    @Test
    void undo_refused_after_extra_charge() {
        String token = adminToken();
        UUID room = newRoom(token, "UC" + tag(), newRoomType(token, "UC" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090300001", "Có Phí"))).getStatusCode().value())
            .isEqualTo(200);
        jdbc.update("INSERT INTO hotel.folio_charge (reservation_id, description, amount_gross_vnd) VALUES (?, ?, ?)",
            id, "Minibar", 50_000L);

        ResponseEntity<String> response = post(RESERVATIONS + "/" + id + "/undo-check-in", token, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(json(response).path("detail").asText()).containsIgnoringCase("charge");
        assertThat(status(id)).isEqualTo("CHECKED_IN");
        assertThat(stayGuestCount(id)).isEqualTo(1);
    }

    @Test
    void undo_refused_when_the_charge_was_voided_too() {
        // a voided charge is still a folio row: the rule is "no folio_charge rows exist"
        String token = adminToken();
        UUID room = newRoom(token, "UV" + tag(), newRoomType(token, "UV" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090300002", "Phí Hủy"))).getStatusCode().value())
            .isEqualTo(200);
        jdbc.update("""
            INSERT INTO hotel.folio_charge (reservation_id, description, amount_gross_vnd, voided_at, void_reason)
            VALUES (?, 'Minibar', 50000, now(), 'oops')
            """, id);

        ResponseEntity<String> response = post(RESERVATIONS + "/" + id + "/undo-check-in", token, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
    }

    @Test
    void undo_refused_next_day() {
        String token = adminToken();
        UUID room = newRoom(token, "UD" + tag(), newRoomType(token, "UD" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(2), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090400001", "Hôm Qua"))).getStatusCode().value())
            .isEqualTo(200);
        Instant yesterday = today.minusDays(1).atTime(12, 0).atZone(HOTEL_ZONE).toInstant();
        jdbc.update("UPDATE hotel.reservation SET checked_in_at = ? WHERE id = ?",
            java.sql.Timestamp.from(yesterday), id);

        ResponseEntity<String> response = post(RESERVATIONS + "/" + id + "/undo-check-in", token, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(status(id)).isEqualTo("CHECKED_IN");
        assertThat(stayGuestCount(id)).isEqualTo(1);
    }

    @Test
    void undo_requires_checked_in_and_known_reservation() {
        String token = adminToken();
        UUID room = newRoom(token, "UR" + tag(), newRoomType(token, "UR" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID confirmed = book(token, room, today, today.plusDays(1), 1);

        ResponseEntity<String> notCheckedIn = post(RESERVATIONS + "/" + confirmed + "/undo-check-in", token, null);
        ResponseEntity<String> unknown = post(RESERVATIONS + "/" + UUID.randomUUID() + "/undo-check-in", token, null);

        assertThat(notCheckedIn.getStatusCode().value()).as(notCheckedIn.getBody()).isEqualTo(409);
        assertThat(json(notCheckedIn).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void undo_keeps_prepayments() {
        String token = adminToken();
        UUID room = newRoom(token, "UP" + tag(), newRoomType(token, "UP" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090500001", "Trả Trước"))).getStatusCode().value())
            .isEqualTo(200);
        jdbc.update("INSERT INTO hotel.folio_payment (reservation_id, method, amount_gross_vnd) VALUES (?, 'CASH', ?)",
            id, 300_000L);

        ResponseEntity<String> response = post(RESERVATIONS + "/" + id + "/undo-check-in", token, null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForList(
            "SELECT amount_gross_vnd FROM hotel.folio_payment WHERE reservation_id = ? AND voided_at IS NULL",
            Long.class, id)).containsExactly(300_000L);
    }

    @Test
    void undo_and_correct_need_stays_checkin_permission() {
        String admin = adminToken();
        UUID room = newRoom(admin, "PE" + tag(), newRoomType(admin, "PE" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(admin, room, today, today.plusDays(1), 1);
        assertThat(checkIn(admin, id, List.of(adult("CCCD", "079090600001", "Quyền"))).getStatusCode().value())
            .isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        String reader = tokenWith("hotel_it_uc_reader", "hotel.stays.read", "hotel.reservations.read");

        ResponseEntity<String> undo = post(RESERVATIONS + "/" + id + "/undo-check-in", reader, null);
        ResponseEntity<String> correct =
            put(RESERVATIONS + "/" + id + "/stay-guests/" + stayGuest, reader, adult("CCCD", "1", "X"), "0");

        assertThat(undo.getStatusCode().value()).as(undo.getBody()).isEqualTo(403);
        assertThat(correct.getStatusCode().value()).as(correct.getBody()).isEqualTo(403);
        assertThat(status(id)).isEqualTo("CHECKED_IN");
    }

    @Test
    void correct_fixes_id_number_and_audits_field_names_only() {
        String token = adminToken();
        UUID room = newRoom(token, "CF" + tag(), newRoomType(token, "CF" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(2), 1);
        String wrongNumber = "0790" + (10_000_000 + (long) (Math.random() * 89_000_000));
        String rightNumber = "0790" + (10_000_000 + (long) (Math.random() * 89_000_000));
        assertThat(checkIn(token, id, List.of(adult("CCCD", wrongNumber, "Nguyễn Sửa"))).getStatusCode().value())
            .isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        Map<String, Object> fixed = adult("CCCD", rightNumber, "Nguyễn Sửa");

        ResponseEntity<String> response = put(RESERVATIONS + "/" + id + "/stay-guests/" + stayGuest, token, fixed, "0");

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode view = data(response);
        assertThat(view.path("id").asText()).isEqualTo(stayGuest.toString());
        assertThat(view.path("idNumber").asText()).isEqualTo(rightNumber);
        assertThat(view.path("version").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT id_number FROM hotel.stay_guest WHERE id = ?", String.class, stayGuest)).isEqualTo(rightNumber);
        assertThat(changes(stayGuest)).containsExactly("StayGuest:CREATE:0", "StayGuest:UPDATE:1");

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT user_id, before_json::text AS before, after_json::text AS after
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND op = 'STAY_GUEST_CORRECT'
              AND after_json ->> 'stayGuestId' = ?
            """, stayGuest.toString());
        assertThat(row.get("user_id")).isNotNull();
        String after = (String) row.get("after");
        JsonNode afterJson = readJson(after);
        assertThat(afterJson.path("stayGuestId").asText()).isEqualTo(stayGuest.toString());
        List<String> changedFields = new ArrayList<>();
        afterJson.path("changedFields").forEach(node -> changedFields.add(node.asText()));
        assertThat(changedFields).containsExactly("idNumber");
        assertThat(afterJson.size()).isEqualTo(2);
        String everything = row.get("before") + after;
        assertThat(everything).doesNotContain(wrongNumber).doesNotContain(rightNumber).doesNotContain("Nguyễn Sửa");
    }

    @Test
    void correct_can_change_several_fields_and_is_validated_like_check_in() {
        String token = adminToken();
        UUID room = newRoom(token, "CM" + tag(), newRoomType(token, "CM" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090700001", "Đa Trường"))).getStatusCode().value())
            .isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        String path = RESERVATIONS + "/" + id + "/stay-guests/" + stayGuest;
        Map<String, Object> noAddress = adult("CCCD", "079090700001", "Đa Trường");
        noAddress.put("address", " ");
        Map<String, Object> foreigner = adult("PASSPORT", "P7654321", "Đa Trường");
        foreigner.put("nationality", "FR");
        foreigner.put("address", null);

        ResponseEntity<String> invalid = put(path, token, noAddress, "0");
        ResponseEntity<String> ok = put(path, token, foreigner, "0");

        assertThat(invalid.getStatusCode().value()).as(invalid.getBody()).isEqualTo(400);
        assertThat(json(invalid).path("code").asText()).isEqualTo("HOTEL_INVALID_ID_DOC");
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
        assertThat(data(ok).path("version").asLong()).isEqualTo(1L);
        List<String> changedFields = new ArrayList<>();
        readJson(jdbc.queryForObject("""
            SELECT after_json::text FROM audit_event
            WHERE plugin_id = 'com.crm.hotel' AND op = 'STAY_GUEST_CORRECT' AND after_json ->> 'stayGuestId' = ?
            """, String.class, stayGuest.toString())).path("changedFields").forEach(n -> changedFields.add(n.asText()));
        assertThat(changedFields).containsExactlyInAnyOrder("idType", "idNumber", "nationality", "address");
    }

    @Test
    void correct_with_stale_if_match_is_409() {
        String token = adminToken();
        UUID room = newRoom(token, "CS" + tag(), newRoomType(token, "CS" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090800001", "Cũ Mới"))).getStatusCode().value())
            .isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        String path = RESERVATIONS + "/" + id + "/stay-guests/" + stayGuest;
        assertThat(put(path, token, adult("CCCD", "079090800002", "Cũ Mới"), "0").getStatusCode().value())
            .isEqualTo(200);

        ResponseEntity<String> stale = put(path, token, adult("CCCD", "079090800003", "Cũ Mới"), "\"0\"");
        ResponseEntity<String> missing = exchange(
            org.springframework.http.HttpMethod.PUT, path, token, adult("CCCD", "079090800003", "Cũ Mới"));
        ResponseEntity<String> garbage = put(path, token, adult("CCCD", "079090800003", "Cũ Mới"), "abc");
        ResponseEntity<String> quotedCurrent = put(path, token, adult("CCCD", "079090800004", "Cũ Mới"), "\"1\"");

        assertThat(stale.getStatusCode().value()).as(stale.getBody()).isEqualTo(409);
        assertThat(json(stale).path("code").asText()).isEqualTo("CONFLICT");
        assertThat(missing.getStatusCode().value()).as(missing.getBody()).isEqualTo(428);
        assertThat(json(missing).path("code").asText()).isEqualTo("HOTEL_VERSION_REQUIRED");
        assertThat(garbage.getStatusCode().value()).as(garbage.getBody()).isEqualTo(428);
        assertThat(quotedCurrent.getStatusCode().value()).as(quotedCurrent.getBody()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
            "SELECT id_number FROM hotel.stay_guest WHERE id = ?", String.class, stayGuest)).isEqualTo("079090800004");
    }

    @Test
    void correct_requires_checked_in() {
        String token = adminToken();
        UUID room = newRoom(token, "CR" + tag(), newRoomType(token, "CR" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090900001", "Rồi Hủy"))).getStatusCode().value())
            .isEqualTo(200);
        UUID stayGuest = stayGuestIds(id).get(0);
        // a stay that is checked out can no longer be corrected (status seeded: check-out arrives in Task 6)
        jdbc.update("UPDATE hotel.reservation SET status = 'CHECKED_OUT' WHERE id = ?", id);
        UUID confirmed = book(token, room, today.plusDays(5), today.plusDays(6), 1);

        ResponseEntity<String> checkedOut = put(
            RESERVATIONS + "/" + id + "/stay-guests/" + stayGuest, token, adult("CCCD", "079090900002", "Rồi Hủy"), "0");
        ResponseEntity<String> notCheckedIn = put(
            RESERVATIONS + "/" + confirmed + "/stay-guests/" + stayGuest, token, adult("CCCD", "1", "X"), "0");

        assertThat(checkedOut.getStatusCode().value()).as(checkedOut.getBody()).isEqualTo(409);
        assertThat(json(checkedOut).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(notCheckedIn.getStatusCode().value()).as(notCheckedIn.getBody()).isEqualTo(409);
        assertThat(json(notCheckedIn).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(jdbc.queryForObject(
            "SELECT id_number FROM hotel.stay_guest WHERE id = ?", String.class, stayGuest)).isEqualTo("079090900001");
    }

    @Test
    void correct_unknown_or_foreign_stay_guest_is_404() {
        String token = adminToken();
        UUID type = newRoomType(token, "CU" + tag(), 2, 900_000, true);
        UUID roomA = newRoom(token, "CU" + tag(), type, true);
        UUID roomB = newRoom(token, "CU" + tag(), type, true);
        LocalDate today = hotelToday();
        UUID a = book(token, roomA, today, today.plusDays(1), 1);
        UUID b = book(token, roomB, today, today.plusDays(1), 1);
        assertThat(checkIn(token, a, List.of(adult("CCCD", "079091000001", "A Một"))).getStatusCode().value())
            .isEqualTo(200);
        assertThat(checkIn(token, b, List.of(adult("CCCD", "079091000002", "B Hai"))).getStatusCode().value())
            .isEqualTo(200);
        UUID ofB = stayGuestIds(b).get(0);

        ResponseEntity<String> unknown = put(RESERVATIONS + "/" + a + "/stay-guests/" + UUID.randomUUID(), token,
            adult("CCCD", "1", "X"), "0");
        ResponseEntity<String> foreign = put(RESERVATIONS + "/" + a + "/stay-guests/" + ofB, token,
            adult("CCCD", "1", "X"), "0");
        ResponseEntity<String> unknownReservation = put(
            RESERVATIONS + "/" + UUID.randomUUID() + "/stay-guests/" + ofB, token, adult("CCCD", "1", "X"), "0");

        for (ResponseEntity<String> response : List.of(unknown, foreign, unknownReservation)) {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        }
        assertThat(jdbc.queryForObject(
            "SELECT id_number FROM hotel.stay_guest WHERE id = ?", String.class, ofB)).isEqualTo("079091000002");
    }

    private ResponseEntity<String> checkIn(String token, UUID id, List<Map<String, Object>> adults) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adults", adults);
        body.put("roomId", null);
        return post(RESERVATIONS + "/" + id + "/check-in", token, body);
    }

    private List<UUID> stayGuestIds(UUID reservationId) {
        return jdbc.queryForList(
            "SELECT id FROM hotel.stay_guest WHERE reservation_id = ? ORDER BY full_name, id", UUID.class, reservationId);
    }

    private JsonNode readJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(text, e);
        }
    }

    private String status(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM hotel.reservation WHERE id = ?", String.class, reservationId);
    }

    private int stayGuestCount(UUID reservationId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM hotel.stay_guest WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private List<String> changes(UUID entityId) {
        return jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? "
                + "ORDER BY version, occurred_at",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, entityId);
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
