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

/** Reservations over HTTP: book, view, list, cancel; overlap rules, guests, audit, change events, permissions. */
class ReservationFlowIT extends HotelITSupport {

    /** Far enough ahead that "today" never interferes. */
    private static final LocalDate IN = LocalDate.now().plusDays(30);
    private static final String RESERVATIONS = HOTEL_API + "/reservations";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void full_flow_book_hides_room_cancel_frees_it() {
        String token = adminToken();
        UUID type = newRoomType(token, "FF" + tag(), 2, 1_250_000, true);
        UUID room = newRoom(token, "FF" + tag(), type, true);
        assertThat(freeRooms(token, IN, IN.plusDays(3), type)).containsExactly(room);

        ResponseEntity<String> booked = post(RESERVATIONS, token,
            reservationBody(null, inlineGuest("Flow Guest " + tag(), phone()), room, IN, IN.plusDays(3), 2));

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        JsonNode reservation = data(booked);
        UUID id = UUID.fromString(reservation.path("id").asText());
        assertThat(reservation.path("code").asText()).matches("^R-\\d{4}-\\d{6}$");
        assertThat(reservation.path("roomId").asText()).isEqualTo(room.toString());
        assertThat(reservation.path("roomNumber").asText()).startsWith("FF");
        assertThat(reservation.path("guestName").asText()).startsWith("Flow Guest ");
        assertThat(reservation.path("checkIn").asText()).isEqualTo(IN.toString());
        assertThat(reservation.path("checkOut").asText()).isEqualTo(IN.plusDays(3).toString());
        assertThat(reservation.path("nights").asInt()).isEqualTo(3);
        assertThat(reservation.path("adults").asInt()).isEqualTo(2);
        assertThat(reservation.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(reservation.path("nightlyRateGrossVnd").asLong()).isEqualTo(1_250_000L);
        assertThat(reservation.path("totalGrossVnd").asLong()).isEqualTo(1_250_000L * 3);
        assertThat(reservation.path("notes").asText()).isEqualTo("Late arrival");
        assertThat(reservation.path("createdAt").isNull()).isFalse();
        assertThat(reservation.path("cancelledAt").isNull()).isTrue();
        assertThat(reservation.path("version").asLong()).isZero();
        assertThat(freeRooms(token, IN, IN.plusDays(3), type)).as("booked room hidden").isEmpty();

        ResponseEntity<String> fetched = get(RESERVATIONS + "/" + id, token);
        assertThat(fetched.getStatusCode().value()).as(fetched.getBody()).isEqualTo(200);
        assertThat(data(fetched).path("code").asText()).isEqualTo(reservation.path("code").asText());
        assertThat(data(fetched).path("guestId").asText()).isEqualTo(reservation.path("guestId").asText());

        ResponseEntity<String> cancelled = post(RESERVATIONS + "/" + id + "/cancel", token, null);
        assertThat(cancelled.getStatusCode().value()).as(cancelled.getBody()).isEqualTo(200);
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(data(cancelled).path("cancelledAt").isNull()).isFalse();
        assertThat(data(cancelled).path("version").asLong()).isEqualTo(1L);
        assertThat(freeRooms(token, IN, IN.plusDays(3), type)).as("cancel frees the room").containsExactly(room);

        UUID guestId = UUID.fromString(reservation.path("guestId").asText());
        ResponseEntity<String> rebooked =
            post(RESERVATIONS, token, reservationBody(guestId, null, room, IN, IN.plusDays(3), 1));
        assertThat(rebooked.getStatusCode().value()).as(rebooked.getBody()).isEqualTo(201);
        assertThat(data(rebooked).path("guestId").asText()).isEqualTo(guestId.toString());
        assertThat(data(rebooked).path("code").asText()).isNotEqualTo(reservation.path("code").asText());
    }

    @Test
    void back_to_back_stays_on_the_same_room_are_allowed() {
        String token = adminToken();
        UUID room = newRoom(token, "BB" + tag(), newRoomType(token, "BB" + tag(), 2, 900_000, true), true);

        ResponseEntity<String> middle = book(token, room, IN.plusDays(2), IN.plusDays(4));
        ResponseEntity<String> after = book(token, room, IN.plusDays(4), IN.plusDays(6));
        ResponseEntity<String> before = book(token, room, IN, IN.plusDays(2));

        assertThat(middle.getStatusCode().value()).as(middle.getBody()).isEqualTo(201);
        assertThat(after.getStatusCode().value()).as(after.getBody()).isEqualTo(201);
        assertThat(before.getStatusCode().value()).as(before.getBody()).isEqualTo(201);
        assertThat(confirmedCount(room)).isEqualTo(3);
    }

    @Test
    void overlapping_booking_is_409_unavailable() {
        String token = adminToken();
        UUID room = newRoom(token, "OV" + tag(), newRoomType(token, "OV" + tag(), 2, 900_000, true), true);
        assertThat(book(token, room, IN, IN.plusDays(3)).getStatusCode().value()).isEqualTo(201);
        String loser = "Overlap Loser " + tag();

        ResponseEntity<String> overlap = post(RESERVATIONS, token,
            reservationBody(null, inlineGuest(loser, phone()), room, IN.plusDays(1), IN.plusDays(2), 1));
        ResponseEntity<String> containing = book(token, room, IN.minusDays(1), IN.plusDays(5));

        assertThat(overlap.getStatusCode().value()).as(overlap.getBody()).isEqualTo(409);
        assertThat(json(overlap).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        assertThat(containing.getStatusCode().value()).as(containing.getBody()).isEqualTo(409);
        assertThat(json(containing).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        assertThat(confirmedCount(room)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest WHERE full_name = ?", Integer.class, loser))
            .as("a failed booking leaves no guest behind").isZero();
    }

    @Test
    void rate_change_does_not_alter_existing_booking() {
        String token = adminToken();
        String code = "RC" + tag();
        UUID type = newRoomType(token, code, 2, 1_000_000, true);
        UUID room = newRoom(token, "RC" + tag(), type, true);
        UUID id = UUID.fromString(data(book(token, room, IN, IN.plusDays(2))).path("id").asText());

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("code", code);
        update.put("nameI18n", Map.of("en", "Room " + code));
        update.put("maxGuests", 2);
        update.put("nightlyRateGrossVnd", 1_500_000);
        update.put("active", true);
        ResponseEntity<String> repriced = put(HOTEL_API + "/room-types/" + type, token, update, "0");
        assertThat(repriced.getStatusCode().value()).as(repriced.getBody()).isEqualTo(200);

        JsonNode reservation = data(get(RESERVATIONS + "/" + id, token));
        assertThat(reservation.path("nightlyRateGrossVnd").asLong()).isEqualTo(1_000_000L);
        assertThat(reservation.path("totalGrossVnd").asLong()).isEqualTo(2_000_000L);
        JsonNode newBooking = data(book(token, room, IN.plusDays(2), IN.plusDays(4)));
        assertThat(newBooking.path("totalGrossVnd").asLong()).isEqualTo(3_000_000L);
    }

    @Test
    void inline_new_guest_is_created() {
        String token = adminToken();
        UUID room = newRoom(token, "NG" + tag(), newRoomType(token, "NG" + tag(), 2, 900_000, true), true);
        String tag = tag();
        String phone = phone();
        Map<String, Object> guest = inlineGuest("  Trần Thị Bích " + tag + " ", " " + phone + " ");
        guest.put("email", "bich@example.com");

        ResponseEntity<String> booked = post(RESERVATIONS, token, reservationBody(null, guest, room, IN, IN.plusDays(1), 1));

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        UUID guestId = UUID.fromString(data(booked).path("guestId").asText());
        assertThat(data(booked).path("guestName").asText()).isEqualTo("Trần Thị Bích " + tag);
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT full_name, phone, email, search_key FROM hotel.guest WHERE id = ?", guestId);
        assertThat(row.get("full_name")).isEqualTo("Trần Thị Bích " + tag);
        assertThat(row.get("phone")).isEqualTo(phone);
        assertThat(row.get("email")).isEqualTo("bich@example.com");
        assertThat(row.get("search_key")).isEqualTo("tran thi bich " + tag + " " + phone);
        assertThat(changes(guestId)).containsExactly("Guest:CREATE:0");

        ResponseEntity<String> invalid = post(RESERVATIONS, token,
            reservationBody(null, inlineGuest(" ", phone), room, IN.plusDays(5), IN.plusDays(6), 1));
        assertThat(invalid.getStatusCode().value()).as(invalid.getBody()).isEqualTo(400);
    }

    @Test
    void both_or_neither_guest_is_400() {
        String token = adminToken();
        UUID room = newRoom(token, "BN" + tag(), newRoomType(token, "BN" + tag(), 2, 900_000, true), true);
        UUID existing = UUID.fromString(data(book(token, room, IN, IN.plusDays(1))).path("guestId").asText());

        ResponseEntity<String> both = post(RESERVATIONS, token, reservationBody(
            existing, inlineGuest("Both " + tag(), phone()), room, IN.plusDays(2), IN.plusDays(3), 1));
        ResponseEntity<String> neither =
            post(RESERVATIONS, token, reservationBody(null, null, room, IN.plusDays(2), IN.plusDays(3), 1));

        assertThat(both.getStatusCode().value()).as(both.getBody()).isEqualTo(400);
        assertThat(json(both).path("code").asText()).isEqualTo("HOTEL_INVALID_GUEST");
        assertThat(neither.getStatusCode().value()).as(neither.getBody()).isEqualTo(400);
        assertThat(json(neither).path("code").asText()).isEqualTo("HOTEL_INVALID_GUEST");
        assertThat(confirmedCount(room)).isEqualTo(1);
    }

    @Test
    void out_of_service_room_is_409_not_bookable() {
        String token = adminToken();
        UUID activeType = newRoomType(token, "OS" + tag(), 2, 900_000, true);
        UUID outOfService = newRoom(token, "OS" + tag(), activeType, false);
        UUID inactiveTypeRoom = newRoom(token, "IA" + tag(), newRoomType(token, "IA" + tag(), 2, 900_000, false), true);

        for (UUID room : List.of(outOfService, inactiveTypeRoom)) {
            ResponseEntity<String> response = book(token, room, IN, IN.plusDays(1));
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_NOT_BOOKABLE");
            assertThat(confirmedCount(room)).isZero();
        }
    }

    @Test
    void stay_rules_are_enforced() {
        String token = adminToken();
        UUID room = newRoom(token, "SR" + tag(), newRoomType(token, "SR" + tag(), 2, 900_000, true), true);
        Map<String, Object> guest = inlineGuest("Rules " + tag(), phone());

        ResponseEntity<String> backwards = post(RESERVATIONS, token, reservationBody(null, guest, room, IN, IN, 1));
        ResponseEntity<String> past = post(RESERVATIONS, token,
            reservationBody(null, guest, room, LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), 1));
        ResponseEntity<String> crowded = post(RESERVATIONS, token, reservationBody(null, guest, room, IN, IN.plusDays(1), 3));

        assertThat(backwards.getStatusCode().value()).as(backwards.getBody()).isEqualTo(400);
        assertThat(json(backwards).path("code").asText()).isEqualTo("HOTEL_INVALID_STAY");
        assertThat(past.getStatusCode().value()).as(past.getBody()).isEqualTo(400);
        assertThat(json(past).path("code").asText()).isEqualTo("HOTEL_INVALID_STAY");
        assertThat(crowded.getStatusCode().value()).as(crowded.getBody()).isEqualTo(400);
        assertThat(json(crowded).path("code").asText()).isEqualTo("HOTEL_TOO_MANY_GUESTS");
        assertThat(confirmedCount(room)).isZero();
    }

    @Test
    void cancel_twice_is_409() {
        String token = adminToken();
        UUID room = newRoom(token, "CT" + tag(), newRoomType(token, "CT" + tag(), 2, 900_000, true), true);
        UUID id = UUID.fromString(data(book(token, room, IN, IN.plusDays(1))).path("id").asText());

        ResponseEntity<String> first = post(RESERVATIONS + "/" + id + "/cancel", token, null);
        ResponseEntity<String> second = post(RESERVATIONS + "/" + id + "/cancel", token, null);

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(409);
        assertThat(json(second).path("code").asText()).isEqualTo("HOTEL_ALREADY_CANCELLED");
        assertThat(data(get(RESERVATIONS + "/" + id, token)).path("version").asLong()).isEqualTo(1L);
    }

    @Test
    void unknown_ids_are_404() {
        String token = adminToken();
        UUID room = newRoom(token, "UK" + tag(), newRoomType(token, "UK" + tag(), 2, 900_000, true), true);
        UUID unknown = UUID.randomUUID();

        List<ResponseEntity<String>> responses = List.of(
            get(RESERVATIONS + "/" + unknown, token),
            post(RESERVATIONS + "/" + unknown + "/cancel", token, null),
            post(RESERVATIONS, token, reservationBody(unknown, null, room, IN, IN.plusDays(1), 1)),
            post(RESERVATIONS, token,
                reservationBody(null, inlineGuest("Nowhere " + tag(), phone()), unknown, IN, IN.plusDays(1), 1)));

        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        }
    }

    @Test
    void audit_rows_written_for_create_and_cancel() {
        String token = adminToken();
        UUID room = newRoom(token, "AU" + tag(), newRoomType(token, "AU" + tag(), 2, 900_000, true), true);
        JsonNode booked = data(book(token, room, IN, IN.plusDays(2)));
        UUID id = UUID.fromString(booked.path("id").asText());
        assertThat(post(RESERVATIONS + "/" + id + "/cancel", token, null).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT op, entity_type, user_id, before_json ->> 'status' AS before_status,
                   after_json ->> 'status' AS after_status, after_json ->> 'code' AS after_code
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? ORDER BY occurred_at, op
            """, id);

        assertThat(rows).extracting(row -> row.get("op")).containsExactly("RESERVATION_CREATE", "RESERVATION_CANCEL");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("entity_type")).isEqualTo("Reservation");
            assertThat(row.get("user_id")).isNotNull();
        });
        assertThat(rows.get(0).get("before_status")).isNull();
        assertThat(rows.get(0).get("after_status")).isEqualTo("CONFIRMED");
        assertThat(rows.get(0).get("after_code")).isEqualTo(booked.path("code").asText());
        assertThat(rows.get(1).get("before_status")).isEqualTo("CONFIRMED");
        assertThat(rows.get(1).get("after_status")).isEqualTo("CANCELLED");
    }

    @Test
    void change_events_written() {
        String token = adminToken();
        UUID room = newRoom(token, "CE" + tag(), newRoomType(token, "CE" + tag(), 2, 900_000, true), true);
        JsonNode booked = data(book(token, room, IN, IN.plusDays(2)));
        UUID id = UUID.fromString(booked.path("id").asText());

        post(RESERVATIONS + "/" + id + "/cancel", token, null);

        assertThat(changes(id)).containsExactly("Reservation:CREATE:0", "Reservation:UPDATE:1");
        assertThat(changes(UUID.fromString(booked.path("guestId").asText()))).containsExactly("Guest:CREATE:0");
    }

    @Test
    void list_by_range_returns_overlapping_only() {
        String token = adminToken();
        UUID type = newRoomType(token, "LR" + tag(), 2, 900_000, true);
        UUID room1 = newRoom(token, "LR" + tag(), type, true);
        UUID room2 = newRoom(token, "LR" + tag(), type, true);
        LocalDate base = IN.plusDays(100);
        UUID endsAtFrom = id(book(token, room1, base, base.plusDays(2)));
        UUID overlapsHead = id(book(token, room2, base.plusDays(1), base.plusDays(3)));
        UUID overlapsTail = id(book(token, room1, base.plusDays(5), base.plusDays(7)));
        UUID startsAtTo = id(book(token, room2, base.plusDays(6), base.plusDays(8)));

        ResponseEntity<String> response =
            get(RESERVATIONS + "?from=" + base.plusDays(2) + "&to=" + base.plusDays(6), token);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<UUID> mine = new ArrayList<>();
        for (JsonNode reservation : data(response)) {
            String roomId = reservation.path("roomId").asText();
            if (roomId.equals(room1.toString()) || roomId.equals(room2.toString())) {
                mine.add(UUID.fromString(reservation.path("id").asText()));
            }
        }
        assertThat(mine).containsExactly(overlapsHead, overlapsTail).doesNotContain(endsAtFrom, startsAtTo);

        for (String query : List.of("", "?from=" + base, "?to=" + base, "?from=nope&to=" + base,
                "?from=" + base + "&to=" + base, "?from=" + base.plusDays(1) + "&to=" + base)) {
            ResponseEntity<String> bad = get(RESERVATIONS + query, token);
            assertThat(bad.getStatusCode().value()).as(query + " " + bad.getBody()).isEqualTo(400);
            assertThat(json(bad).path("code").asText()).isEqualTo("HOTEL_INVALID_QUERY");
        }
    }

    @Test
    void user_without_create_permission_gets_403() {
        String admin = adminToken();
        UUID room = newRoom(admin, "PM" + tag(), newRoomType(admin, "PM" + tag(), 2, 900_000, true), true);
        UUID id = id(book(admin, room, IN, IN.plusDays(1)));
        String reader = tokenWith("hotel.reservations.read");

        ResponseEntity<String> create = post(RESERVATIONS, reader,
            reservationBody(null, inlineGuest("Forbidden " + tag(), phone()), room, IN.plusDays(3), IN.plusDays(4), 1));
        ResponseEntity<String> cancel = post(RESERVATIONS + "/" + id + "/cancel", reader, null);
        ResponseEntity<String> read = get(RESERVATIONS + "/" + id, reader);
        ResponseEntity<String> anonymous = get(RESERVATIONS + "/" + id, null);

        assertThat(create.getStatusCode().value()).as(create.getBody()).isEqualTo(403);
        assertThat(cancel.getStatusCode().value()).as(cancel.getBody()).isEqualTo(403);
        assertThat(read.getStatusCode().value()).as(read.getBody()).isEqualTo(200);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(confirmedCount(room)).isEqualTo(1);
    }

    /** Books {@code room} over {@code [in, out)} for one adult with a fresh inline guest. */
    private ResponseEntity<String> book(String token, UUID room, LocalDate in, LocalDate out) {
        return post(RESERVATIONS, token, reservationBody(null, inlineGuest("Guest " + tag(), phone()), room, in, out, 1));
    }

    private UUID id(ResponseEntity<String> created) {
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    private List<UUID> freeRooms(String token, LocalDate in, LocalDate out, UUID roomTypeId) {
        ResponseEntity<String> response =
            get(HOTEL_API + "/availability?checkIn=" + in + "&checkOut=" + out + "&adults=1", token);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<UUID> ids = new ArrayList<>();
        for (JsonNode type : data(response).path("roomTypes")) {
            if (roomTypeId.toString().equals(type.path("roomTypeId").asText())) {
                type.path("rooms").forEach(room -> ids.add(UUID.fromString(room.path("id").asText())));
            }
        }
        return ids;
    }

    private int confirmedCount(UUID room) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM hotel.reservation WHERE status = 'CONFIRMED' AND room_id = ?", Integer.class, room);
    }

    private List<String> changes(UUID entityId) {
        return jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? ORDER BY version",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, entityId);
    }

    /** Access token of a persisted non-admin user holding only {@code permissions}. */
    private String tokenWith(String... permissions) {
        String username = "hotel_it_res_reader";
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

    private static String phone() {
        return "09" + (10_000_000 + (long) (Math.random() * 89_000_000));
    }
}
