package com.crm.foundation.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Availability search over HTTP: free rooms by type with totals, overlap rules, capacity, validation. */
class AvailabilityIT extends HotelITSupport {

    /** Far enough ahead that "today" never interferes, near enough to be a normal booking. */
    private static final LocalDate IN = LocalDate.now().plusDays(40);

    @Test
    void free_rooms_grouped_by_type_with_totals() {
        String token = adminToken();
        String tag = tag();
        UUID doubles = createRoomType(token, "A" + tag, 2, 1_200_000, true);
        UUID suites = createRoomType(token, "B" + tag, 4, 2_000_000, true);
        UUID d2 = createRoom(token, tag + "-2", doubles, 2, true);
        UUID d1 = createRoom(token, tag + "-1", doubles, 1, true);
        UUID s1 = createRoom(token, tag + "-3", suites, null, true);

        ResponseEntity<String> response = availability(token, IN, IN.plusDays(3), 1);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode data = data(response);
        assertThat(data.path("checkIn").asText()).isEqualTo(IN.toString());
        assertThat(data.path("checkOut").asText()).isEqualTo(IN.plusDays(3).toString());
        assertThat(data.path("nights").asInt()).isEqualTo(3);
        JsonNode doubleType = type(data, doubles);
        assertThat(doubleType.path("code").asText()).isEqualTo("A" + tag);
        assertThat(doubleType.path("nameI18n").path("en").asText()).isEqualTo("Double room A" + tag);
        assertThat(doubleType.path("nightlyRateGrossVnd").asLong()).isEqualTo(1_200_000L);
        assertThat(doubleType.path("totalGrossVnd").asLong()).isEqualTo(3_600_000L);
        assertThat(roomIds(doubleType)).containsExactly(d1, d2);
        assertThat(roomNumbers(doubleType)).containsExactly(tag + "-1", tag + "-2");
        assertThat(doubleType.path("rooms").get(0).path("floor").asInt()).isEqualTo(1);
        JsonNode suiteType = type(data, suites);
        assertThat(suiteType.path("totalGrossVnd").asLong()).isEqualTo(6_000_000L);
        assertThat(roomIds(suiteType)).containsExactly(s1);
        assertThat(suiteType.path("rooms").get(0).path("floor").isNull()).isTrue();
        assertThat(typeCodes(data)).containsSubsequence("A" + tag, "B" + tag);
    }

    @Test
    void booked_room_hidden_for_overlap() {
        String token = adminToken();
        UUID type = createRoomType(token, "OV" + tag(), 2, 1_000_000, true);
        UUID room = createRoom(token, "OV" + tag(), type, 1, true);
        seedReservation(room, IN.plusDays(2), IN.plusDays(5), "CONFIRMED");

        assertThat(freeRooms(token, IN, IN.plusDays(3), 1, type)).as("tail overlaps").isEmpty();
        assertThat(freeRooms(token, IN.plusDays(4), IN.plusDays(7), 1, type)).as("head overlaps").isEmpty();
        assertThat(freeRooms(token, IN.plusDays(3), IN.plusDays(4), 1, type)).as("inside").isEmpty();
        assertThat(freeRooms(token, IN, IN.plusDays(9), 1, type)).as("contains").isEmpty();
        assertThat(freeRooms(token, IN.plusDays(2), IN.plusDays(5), 1, type)).as("same stay").isEmpty();
    }

    @Test
    void booked_room_visible_for_back_to_back_dates() {
        String token = adminToken();
        UUID type = createRoomType(token, "BB" + tag(), 2, 1_000_000, true);
        UUID room = createRoom(token, "BB" + tag(), type, 1, true);
        seedReservation(room, IN.plusDays(2), IN.plusDays(4), "CONFIRMED");

        assertThat(freeRooms(token, IN.plusDays(4), IN.plusDays(6), 1, type)).as("starts at check-out").containsExactly(room);
        assertThat(freeRooms(token, IN, IN.plusDays(2), 1, type)).as("ends at check-in").containsExactly(room);
    }

    @Test
    void cancelled_booking_does_not_block() {
        String token = adminToken();
        UUID type = createRoomType(token, "CA" + tag(), 2, 1_000_000, true);
        UUID room = createRoom(token, "CA" + tag(), type, 1, true);
        seedReservation(room, IN, IN.plusDays(3), "CANCELLED");

        assertThat(freeRooms(token, IN, IN.plusDays(3), 1, type)).containsExactly(room);
    }

    @Test
    void out_of_service_room_hidden() {
        String token = adminToken();
        UUID type = createRoomType(token, "OS" + tag(), 2, 1_000_000, true);
        UUID inService = createRoom(token, "OS" + tag(), type, 1, true);
        createRoom(token, "OS" + tag(), type, 1, false);
        UUID inactiveType = createRoomType(token, "IA" + tag(), 2, 1_000_000, false);
        createRoom(token, "IA" + tag(), inactiveType, 1, true);

        ResponseEntity<String> response = availability(token, IN, IN.plusDays(1), 1);

        assertThat(roomIds(type(data(response), type))).containsExactly(inService);
        assertThat(type(data(response), inactiveType)).as("inactive type").isNull();
    }

    @Test
    void types_smaller_than_party_omitted() {
        String token = adminToken();
        String tag = tag();
        UUID small = createRoomType(token, "SM" + tag, 2, 1_000_000, true);
        UUID big = createRoomType(token, "BG" + tag, 4, 1_500_000, true);
        createRoom(token, "SM" + tag, small, 1, true);
        UUID bigRoom = createRoom(token, "BG" + tag, big, 1, true);

        ResponseEntity<String> response = availability(token, IN, IN.plusDays(2), 3);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(type(data(response), small)).isNull();
        assertThat(roomIds(type(data(response), big))).containsExactly(bigRoom);
        assertThat(roomIds(type(data(availability(token, IN, IN.plusDays(2), 2)), small))).hasSize(1);
    }

    @Test
    void invalid_dates_400() {
        String token = adminToken();
        String base = HOTEL_API + "/availability";
        LocalDate today = LocalDate.now();

        assertBadRequest(get(base + "?checkIn=" + IN + "&checkOut=" + IN + "&adults=1", token), "HOTEL_INVALID_STAY");
        assertBadRequest(get(base + "?checkIn=" + IN.plusDays(2) + "&checkOut=" + IN + "&adults=1", token), "HOTEL_INVALID_STAY");
        assertBadRequest(get(base + "?checkIn=" + IN + "&checkOut=" + IN.plusDays(61) + "&adults=1", token), "HOTEL_INVALID_STAY");
        assertBadRequest(get(base + "?checkIn=" + today.minusDays(3) + "&checkOut=" + today.plusDays(1) + "&adults=1", token), "HOTEL_INVALID_STAY");
        assertBadRequest(get(base + "?checkIn=" + IN + "&checkOut=" + IN.plusDays(1) + "&adults=0", token), "HOTEL_TOO_MANY_GUESTS");
        assertBadRequest(get(base + "?checkIn=not-a-date&checkOut=" + IN + "&adults=1", token), "HOTEL_INVALID_QUERY");
        assertBadRequest(get(base + "?checkIn=" + IN + "&checkOut=2026-13-45&adults=1", token), "HOTEL_INVALID_QUERY");
        assertBadRequest(get(base + "?checkIn=" + IN + "&checkOut=" + IN.plusDays(1) + "&adults=two", token), "HOTEL_INVALID_QUERY");
        assertBadRequest(get(base + "?checkOut=" + IN + "&adults=1", token), "HOTEL_INVALID_QUERY");
        assertBadRequest(get(base + "?checkIn=" + IN + "&adults=1", token), "HOTEL_INVALID_QUERY");
        assertBadRequest(get(base + "?checkIn=" + IN + "&checkOut=" + IN.plusDays(1), token), "HOTEL_INVALID_QUERY");
        assertBadRequest(get(base, token), "HOTEL_INVALID_QUERY");
    }

    @Test
    void anonymous_request_is_401() {
        assertThat(get(HOTEL_API + "/availability?checkIn=" + IN + "&checkOut=" + IN.plusDays(1) + "&adults=1", null)
            .getStatusCode().value()).isEqualTo(401);
    }

    private void assertBadRequest(ResponseEntity<String> response, String code) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }

    private ResponseEntity<String> availability(String token, LocalDate in, LocalDate out, int adults) {
        return get(HOTEL_API + "/availability?checkIn=" + in + "&checkOut=" + out + "&adults=" + adults, token);
    }

    /** Ids of the free rooms of one room type for the stay. */
    private List<UUID> freeRooms(String token, LocalDate in, LocalDate out, int adults, UUID roomTypeId) {
        ResponseEntity<String> response = availability(token, in, out, adults);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode type = type(data(response), roomTypeId);
        return type == null ? List.of() : roomIds(type);
    }

    /** The entry of one room type, or {@code null} when the response omits it. */
    private static JsonNode type(JsonNode data, UUID roomTypeId) {
        for (JsonNode type : data.path("roomTypes")) {
            if (roomTypeId.toString().equals(type.path("roomTypeId").asText())) {
                return type;
            }
        }
        return null;
    }

    private static List<String> typeCodes(JsonNode data) {
        List<String> codes = new ArrayList<>();
        data.path("roomTypes").forEach(type -> codes.add(type.path("code").asText()));
        return codes;
    }

    private static List<UUID> roomIds(JsonNode type) {
        List<UUID> ids = new ArrayList<>();
        type.path("rooms").forEach(room -> ids.add(UUID.fromString(room.path("id").asText())));
        return ids;
    }

    private static List<String> roomNumbers(JsonNode type) {
        List<String> numbers = new ArrayList<>();
        type.path("rooms").forEach(room -> numbers.add(room.path("number").asText()));
        return numbers;
    }

    private UUID createRoomType(String token, String code, int maxGuests, long rate, boolean active) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("vi", "Phòng đôi " + code);
        names.put("en", "Double room " + code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("nameI18n", names);
        body.put("maxGuests", maxGuests);
        body.put("nightlyRateGrossVnd", rate);
        body.put("active", active);
        ResponseEntity<String> created = post(HOTEL_API + "/room-types", token, body);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    private UUID createRoom(String token, String number, UUID roomTypeId, Integer floor, boolean inService) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", number);
        body.put("roomTypeId", roomTypeId);
        body.put("floor", floor);
        body.put("inService", inService);
        ResponseEntity<String> created = post(HOTEL_API + "/rooms", token, body);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    /** Six hex characters: short enough for the 16-character room number column. */
    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
