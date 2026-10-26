package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Check-in over HTTP: identity records, the check-in day, capacity, room swap, occupancy, audit and sync. */
class CheckInIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void check_in_records_adults_and_marks_checked_in() {
        String token = adminToken();
        UUID room = newRoom(token, "CI" + tag(), newRoomType(token, "CI" + tag(), 3, 1_100_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(2), 1);
        Map<String, Object> first = adult("CCCD", "079090001234", "Nguyễn Văn An");
        Map<String, Object> second = adult("PASSPORT", "X9876543", "Marie Curie");
        second.put("nationality", "FR");
        second.put("address", null);
        second.put("visaNumber", "VN-EV-2026-1");
        second.put("entryDate", today.minusDays(3));

        ResponseEntity<String> checkedIn = checkIn(token, id, List.of(first, second), null);

        assertThat(checkedIn.getStatusCode().value()).as(checkedIn.getBody()).isEqualTo(200);
        JsonNode view = data(checkedIn);
        assertThat(view.path("id").asText()).isEqualTo(id.toString());
        assertThat(view.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(view.path("adults").asInt()).isEqualTo(2);
        assertThat(view.path("checkedInAt").isNull()).isFalse();
        assertThat(view.path("roomId").asText()).isEqualTo(room.toString());
        assertThat(view.path("version").asLong()).isEqualTo(1L);
        JsonNode fetched = data(get(RESERVATIONS + "/" + id, token));
        assertThat(fetched.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(fetched.path("checkedInAt").asText()).isEqualTo(view.path("checkedInAt").asText());

        ResponseEntity<String> guests = get(RESERVATIONS + "/" + id + "/stay-guests", token);
        assertThat(guests.getStatusCode().value()).as(guests.getBody()).isEqualTo(200);
        assertThat(data(guests)).hasSize(2);
        JsonNode marie = byName(data(guests), "Marie Curie");
        assertThat(marie.path("id").asText()).isNotBlank();
        assertThat(marie.path("idType").asText()).isEqualTo("PASSPORT");
        assertThat(marie.path("idNumber").asText()).isEqualTo("X9876543");
        assertThat(marie.path("dateOfBirth").asText()).isEqualTo("1990-05-17");
        assertThat(marie.path("nationality").asText()).isEqualTo("FR");
        assertThat(marie.path("address").isNull()).isTrue();
        assertThat(marie.path("visaNumber").asText()).isEqualTo("VN-EV-2026-1");
        assertThat(marie.path("entryDate").asText()).isEqualTo(today.minusDays(3).toString());
        assertThat(marie.path("version").asLong()).isZero();
        JsonNode an = byName(data(guests), "Nguyễn Văn An");
        assertThat(an.path("idType").asText()).isEqualTo("CCCD");
        assertThat(an.path("idNumber").asText()).isEqualTo("079090001234");
        assertThat(an.path("nationality").asText()).isEqualTo("VN");
        assertThat(an.path("address").asText()).isEqualTo("12 Lê Lợi, Phú Hội, Huế");
        assertThat(an.path("visaNumber").isNull()).isTrue();
        assertThat(an.path("entryDate").isNull()).isTrue();
    }

    @Test
    void stay_guests_need_stays_read_permission() {
        String admin = adminToken();
        UUID room = newRoom(admin, "SP" + tag(), newRoomType(admin, "SP" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(admin, room, today, today.plusDays(1), 1);
        String reservationReader = tokenWith("hotel_it_ci_res_reader", "hotel.reservations.read");
        String stayReader = tokenWith("hotel_it_ci_stay_reader", "hotel.stays.read");

        ResponseEntity<String> checkInForbidden = checkIn(reservationReader, id, List.of(adult("CCCD", "001", "A")), null);
        assertThat(checkInForbidden.getStatusCode().value()).as(checkInForbidden.getBody()).isEqualTo(403);
        assertThat(checkIn(admin, id, List.of(adult("CCCD", "079090001111", "Trần Bình")), null)
            .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> forbidden = get(RESERVATIONS + "/" + id + "/stay-guests", reservationReader);
        ResponseEntity<String> allowed = get(RESERVATIONS + "/" + id + "/stay-guests", stayReader);
        ResponseEntity<String> unknown = get(RESERVATIONS + "/" + UUID.randomUUID() + "/stay-guests", stayReader);

        assertThat(forbidden.getStatusCode().value()).as(forbidden.getBody()).isEqualTo(403);
        assertThat(allowed.getStatusCode().value()).as(allowed.getBody()).isEqualTo(200);
        assertThat(data(allowed)).hasSize(1);
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        ResponseEntity<String> unknownCheckIn =
            checkIn(admin, UUID.randomUUID(), List.of(adult("CCCD", "079090002222", "Lê Chi")), null);
        assertThat(unknownCheckIn.getStatusCode().value()).as(unknownCheckIn.getBody()).isEqualTo(404);
        assertThat(json(unknownCheckIn).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void vn_adult_without_address_is_400_invalid_id_doc() {
        String token = adminToken();
        UUID room = newRoom(token, "VA" + tag(), newRoomType(token, "VA" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        Map<String, Object> noAddress = adult("CCCD", "079090003333", "Phạm Dũng");
        noAddress.put("address", " ");
        Map<String, Object> futureEntry = adult("PASSPORT", "Y1234567", "John Smith");
        futureEntry.put("nationality", "GB");
        futureEntry.put("address", null);
        // the entry date is checked against the calendar date, which runs a day ahead of the business date before 06:00
        futureEntry.put("entryDate", today.plusDays(2));

        ResponseEntity<String> withoutAddress = checkIn(token, id, List.of(noAddress), null);
        ResponseEntity<String> enteredTomorrow = checkIn(token, id, List.of(futureEntry), null);

        assertThat(withoutAddress.getStatusCode().value()).as(withoutAddress.getBody()).isEqualTo(400);
        assertThat(json(withoutAddress).path("code").asText()).isEqualTo("HOTEL_INVALID_ID_DOC");
        assertThat(enteredTomorrow.getStatusCode().value()).as(enteredTomorrow.getBody()).isEqualTo(400);
        assertThat(json(enteredTomorrow).path("code").asText()).isEqualTo("HOTEL_INVALID_ID_DOC");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(stayGuestCount(id)).isZero();
    }

    @Test
    void too_many_adults_is_400_too_many_guests() {
        String token = adminToken();
        UUID room = newRoom(token, "TM" + tag(), newRoomType(token, "TM" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 2);

        ResponseEntity<String> response = checkIn(token, id, List.of(
            adult("CCCD", "079090004441", "Một"), adult("CCCD", "079090004442", "Hai"),
            adult("CCCD", "079090004443", "Ba")), null);
        ResponseEntity<String> none = checkIn(token, id, List.of(), null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_TOO_MANY_GUESTS");
        assertThat(none.getStatusCode().value()).as(none.getBody()).isEqualTo(400);
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(stayGuestCount(id)).isZero();
    }

    @Test
    void cannot_check_in_twice() {
        String token = adminToken();
        UUID room = newRoom(token, "TW" + tag(), newRoomType(token, "TW" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);

        ResponseEntity<String> first = checkIn(token, id, List.of(adult("CCCD", "079090005555", "Đỗ Em")), null);
        ResponseEntity<String> second = checkIn(token, id, List.of(adult("CCCD", "079090005556", "Đỗ Phúc")), null);

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(409);
        assertThat(json(second).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(stayGuestCount(id)).isEqualTo(1);
    }

    @Test
    void cannot_check_in_before_check_in_day() {
        String token = adminToken();
        UUID room = newRoom(token, "EA" + tag(), newRoomType(token, "EA" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today.plusDays(1), today.plusDays(3), 1);

        ResponseEntity<String> response = checkIn(token, id, List.of(adult("CCCD", "079090006666", "Hồ Giang")), null);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_NOT_CHECKIN_DAY");
        assertThat(status(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void checked_in_stay_cannot_be_cancelled() {
        String token = adminToken();
        UUID room = newRoom(token, "CC" + tag(), newRoomType(token, "CC" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(2), 1);
        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090007777", "Vũ Hà")), null)
            .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> cancel = post(RESERVATIONS + "/" + id + "/cancel", token, null);

        assertThat(cancel.getStatusCode().value()).as(cancel.getBody()).isEqualTo(409);
        assertThat(json(cancel).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(status(id)).isEqualTo("CHECKED_IN");
    }

    @Test
    void swap_to_same_type_room() {
        String token = adminToken();
        UUID type = newRoomType(token, "SW" + tag(), 2, 1_300_000, true);
        UUID booked = newRoom(token, "SW" + tag(), type, true);
        String swapNumber = "SW" + tag();
        UUID swapped = newRoom(token, swapNumber, type, true);
        LocalDate today = hotelToday();
        UUID id = book(token, booked, today, today.plusDays(2), 1);

        ResponseEntity<String> response =
            checkIn(token, id, List.of(adult("CCCD", "079090008888", "Lý Khoa")), swapped);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode view = data(response);
        assertThat(view.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(view.path("roomId").asText()).isEqualTo(swapped.toString());
        assertThat(view.path("roomNumber").asText()).isEqualTo(swapNumber);
        assertThat(view.path("nightlyRateGrossVnd").asLong()).isEqualTo(1_300_000L);
        assertThat(view.path("totalGrossVnd").asLong()).isEqualTo(2_600_000L);
        assertThat(jdbc.queryForObject("SELECT room_id FROM hotel.reservation WHERE id = ?", UUID.class, id))
            .isEqualTo(swapped);
        // the original room is free again for the stay's nights
        assertThat(book(token, booked, today, today.plusDays(2), 1)).isNotNull();
    }

    @Test
    void swap_to_other_type_is_409_not_bookable() {
        String token = adminToken();
        UUID type = newRoomType(token, "ST" + tag(), 2, 900_000, true);
        UUID booked = newRoom(token, "ST" + tag(), type, true);
        UUID otherType = newRoom(token, "OT" + tag(), newRoomType(token, "OT" + tag(), 2, 900_000, true), true);
        UUID outOfService = newRoom(token, "OS" + tag(), type, false);
        LocalDate today = hotelToday();
        UUID id = book(token, booked, today, today.plusDays(1), 1);

        for (UUID target : List.of(otherType, outOfService)) {
            ResponseEntity<String> response =
                checkIn(token, id, List.of(adult("CCCD", "079090009999", "Mai Lan")), target);
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_NOT_BOOKABLE");
        }
        ResponseEntity<String> unknown =
            checkIn(token, id, List.of(adult("CCCD", "079090009999", "Mai Lan")), UUID.randomUUID());
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT room_id FROM hotel.reservation WHERE id = ?", UUID.class, id))
            .isEqualTo(booked);
        assertThat(stayGuestCount(id)).isZero();
    }

    /** Review focus 4: the target room is free tonight but booked for a later night of the stay. */
    @Test
    void swap_into_room_booked_later_in_stay_is_409() {
        String token = adminToken();
        UUID type = newRoomType(token, "SL" + tag(), 2, 900_000, true);
        UUID booked = newRoom(token, "SL" + tag(), type, true);
        UUID target = newRoom(token, "SL" + tag(), type, true);
        LocalDate today = hotelToday();
        UUID id = book(token, booked, today, today.plusDays(3), 1);
        book(token, target, today.plusDays(1), today.plusDays(2), 1);

        ResponseEntity<String> response =
            checkIn(token, id, List.of(adult("CCCD", "079090001010", "Tô Minh")), target);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT room_id FROM hotel.reservation WHERE id = ?", UUID.class, id))
            .isEqualTo(booked);
        assertThat(stayGuestCount(id)).isZero();
    }

    @Test
    void check_in_into_occupied_room_is_409_occupied() {
        String token = adminToken();
        UUID type = newRoomType(token, "OC" + tag(), 2, 900_000, true);
        UUID room = newRoom(token, "OC" + tag(), type, true);
        UUID occupiedTarget = newRoom(token, "OC" + tag(), type, true);
        UUID freeRoom = newRoom(token, "OC" + tag(), type, true);
        LocalDate today = hotelToday();
        // overstayers: still checked in although their check-out day has passed
        seedReservation(room, today.minusDays(3), today, "CHECKED_IN");
        seedReservation(occupiedTarget, today.minusDays(2), today.minusDays(1), "CHECKED_IN");
        UUID inOccupiedRoom = book(token, room, today, today.plusDays(2), 1);
        UUID swapper = book(token, freeRoom, today, today.plusDays(2), 1);

        ResponseEntity<String> direct =
            checkIn(token, inOccupiedRoom, List.of(adult("CCCD", "079090001111", "Quách Nam")), null);
        ResponseEntity<String> swap =
            checkIn(token, swapper, List.of(adult("CCCD", "079090001112", "Quách Oanh")), occupiedTarget);

        assertThat(direct.getStatusCode().value()).as(direct.getBody()).isEqualTo(409);
        assertThat(json(direct).path("code").asText()).isEqualTo("HOTEL_ROOM_OCCUPIED");
        assertThat(swap.getStatusCode().value()).as(swap.getBody()).isEqualTo(409);
        assertThat(json(swap).path("code").asText()).isEqualTo("HOTEL_ROOM_OCCUPIED");
        assertThat(status(inOccupiedRoom)).isEqualTo("CONFIRMED");
        assertThat(status(swapper)).isEqualTo("CONFIRMED");
    }

    @Test
    void audit_has_no_identity_data() {
        String token = adminToken();
        UUID room = newRoom(token, "AD" + tag(), newRoomType(token, "AD" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);
        String idNumber = "0790" + (10_000_000 + (long) (Math.random() * 89_000_000));
        String name = "Bí Mật " + tag();
        Map<String, Object> secret = adult("CCCD", idNumber, name);
        secret.put("address", "99 Secret Street " + tag());

        assertThat(checkIn(token, id, List.of(secret, adult("PASSPORT", "Z7654321", "Other " + tag())), null)
            .getStatusCode().value()).isEqualTo(200);

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT entity_type, user_id, before_json ->> 'status' AS before_status, after_json::text AS after
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = 'RESERVATION_CHECK_IN'
            ORDER BY occurred_at DESC LIMIT 1
            """, id);
        assertThat(row.get("entity_type")).isEqualTo("Reservation");
        assertThat(row.get("user_id")).isNotNull();
        assertThat(row.get("before_status")).isEqualTo("CONFIRMED");
        String after = (String) row.get("after");
        assertThat(after).doesNotContain(idNumber).doesNotContain(name).doesNotContain("Z7654321")
            .doesNotContain("Secret Street").doesNotContain("1990-05-17");
        JsonNode afterJson = readJson(after);
        assertThat(afterJson.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(afterJson.path("id").asText()).isEqualTo(id.toString());
        assertThat(afterJson.path("adults").asInt()).isEqualTo(2);
        List<String> stayGuestIds = new ArrayList<>();
        afterJson.path("stayGuestIds").forEach(node -> stayGuestIds.add(node.asText()));
        assertThat(stayGuestIds).containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
            "SELECT id::text FROM hotel.stay_guest WHERE reservation_id = ?", String.class, id));
    }

    @Test
    void change_events_written() {
        String token = adminToken();
        UUID room = newRoom(token, "CE" + tag(), newRoomType(token, "CE" + tag(), 2, 900_000, true), true);
        LocalDate today = hotelToday();
        UUID id = book(token, room, today, today.plusDays(1), 1);

        assertThat(checkIn(token, id, List.of(adult("CCCD", "079090001313", "Hà Sơn"),
            adult("CCCD", "079090001314", "Hà Thu")), null).getStatusCode().value()).isEqualTo(200);

        assertThat(changes(id)).containsExactly("Reservation:CREATE:0", "Reservation:UPDATE:1");
        List<UUID> stayGuests = jdbc.queryForList(
            "SELECT id FROM hotel.stay_guest WHERE reservation_id = ?", UUID.class, id);
        assertThat(stayGuests).hasSize(2);
        for (UUID stayGuest : stayGuests) {
            assertThat(changes(stayGuest)).containsExactly("StayGuest:CREATE:0");
        }
    }

    private ResponseEntity<String> checkIn(String token, UUID id, List<Map<String, Object>> adults, UUID roomId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adults", adults);
        body.put("roomId", roomId);
        return post(RESERVATIONS + "/" + id + "/check-in", token, body);
    }

    private static JsonNode byName(JsonNode stayGuests, String fullName) {
        for (JsonNode guest : stayGuests) {
            if (fullName.equals(guest.path("fullName").asText())) {
                return guest;
            }
        }
        throw new AssertionError("no stay guest named " + fullName + " in " + stayGuests);
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
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? ORDER BY version",
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
