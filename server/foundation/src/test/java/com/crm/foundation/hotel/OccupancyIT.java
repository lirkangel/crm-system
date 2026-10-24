package com.crm.foundation.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A room with a checked-in stay stays blocked: availability, booking and the database constraint agree. */
class OccupancyIT extends HotelITSupport {

    private static final LocalDate IN = LocalDate.now().plusDays(40);

    @Test
    void checked_in_stay_hides_room_from_availability() {
        String token = adminToken();
        UUID type = newRoomType(token, "OC" + tag(), 2, 1_000_000, true);
        UUID room = newRoom(token, "OC" + tag(), type, true);
        assertThat(freeRooms(token, IN, IN.plusDays(2), type)).as("free before").containsExactly(room);

        seedReservation(room, IN, IN.plusDays(3), "CHECKED_IN");

        assertThat(freeRooms(token, IN, IN.plusDays(2), type)).as("overlapping the occupied stay").isEmpty();
        assertThat(freeRooms(token, IN.plusDays(3), IN.plusDays(5), type)).as("back to back").containsExactly(room);
    }

    @Test
    void booking_over_checked_in_stay_is_409() {
        String token = adminToken();
        UUID room = newRoom(token, "OB" + tag(), newRoomType(token, "OB" + tag(), 2, 1_000_000, true), true);
        seedReservation(room, IN, IN.plusDays(3), "CHECKED_IN");

        ResponseEntity<String> response = post(HOTEL_API + "/reservations", token,
            reservationBody(null, inlineGuest("Blocked Guest " + tag(), "0911222333"), room, IN.plusDays(1), IN.plusDays(2), 1));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
    }

    @Test
    void constraint_rejects_overlapping_insert_while_checked_in() {
        String token = adminToken();
        UUID room = newRoom(token, "OK" + tag(), newRoomType(token, "OK" + tag(), 2, 1_000_000, true), true);
        seedReservation(room, IN, IN.plusDays(3), "CHECKED_IN");

        Throwable error = catchThrowable(() -> seedReservation(room, IN.plusDays(1), IN.plusDays(2), "CONFIRMED"));

        assertThat(error).isNotNull();
        assertThat(sqlState(error)).isEqualTo("23P01");
        Throwable secondError =
            catchThrowable(() -> seedReservation(room, IN.plusDays(2), IN.plusDays(4), "CHECKED_IN"));
        assertThat(secondError).as("two checked-in stays overlapping").isNotNull();
        assertThat(sqlState(secondError)).isEqualTo("23P01");
    }

    @Test
    void checked_out_and_cancelled_do_not_block() {
        String token = adminToken();
        UUID type = newRoomType(token, "OD" + tag(), 2, 1_000_000, true);
        UUID room = newRoom(token, "OD" + tag(), type, true);
        seedReservation(room, IN, IN.plusDays(3), "CHECKED_OUT");
        seedReservation(room, IN, IN.plusDays(3), "CANCELLED");

        assertThat(freeRooms(token, IN, IN.plusDays(3), type)).containsExactly(room);
        ResponseEntity<String> response = post(HOTEL_API + "/reservations", token,
            reservationBody(null, inlineGuest("Free Guest " + tag(), "0911222444"), room, IN, IN.plusDays(3), 1));
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
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

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
