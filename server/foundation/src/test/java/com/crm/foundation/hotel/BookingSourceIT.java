package com.crm.foundation.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** How a reservation was booked: the optional source fields of {@code POST /reservations}. */
class BookingSourceIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Test
    void defaults_to_walk_in() {
        String token = adminToken();
        UUID room = newRoom(token);

        ResponseEntity<String> booked = book(token, room, 0, Map.of());

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        JsonNode view = data(booked);
        assertThat(view.path("source").asText()).isEqualTo("WALK_IN");
        assertThat(view.path("otaChannel").isNull()).isTrue();
        assertThat(view.path("otaReference").isNull()).isTrue();
        assertThat(view.path("checkedInAt").isNull()).isTrue();
        assertThat(view.path("checkedOutAt").isNull()).isTrue();
        assertThat(data(get(RESERVATIONS + "/" + view.path("id").asText(), token)).path("source").asText())
            .isEqualTo("WALK_IN");
    }

    @Test
    void ota_booking_keeps_channel_and_reference() {
        String token = adminToken();
        UUID room = newRoom(token);

        ResponseEntity<String> booked = book(token, room, 0,
            Map.of("source", "OTA", "otaChannel", "AGODA", "otaReference", "AG-12345"));

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        JsonNode view = data(booked);
        assertThat(view.path("source").asText()).isEqualTo("OTA");
        assertThat(view.path("otaChannel").asText()).isEqualTo("AGODA");
        assertThat(view.path("otaReference").asText()).isEqualTo("AG-12345");
        JsonNode fetched = data(get(RESERVATIONS + "/" + view.path("id").asText(), token));
        assertThat(fetched.path("source").asText()).isEqualTo("OTA");
        assertThat(fetched.path("otaChannel").asText()).isEqualTo("AGODA");
        assertThat(fetched.path("otaReference").asText()).isEqualTo("AG-12345");
    }

    @Test
    void phone_booking_may_carry_a_reference_but_no_channel() {
        String token = adminToken();
        UUID room = newRoom(token);

        ResponseEntity<String> booked = book(token, room, 0, Map.of("source", "PHONE", "otaReference", "call-77"));

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        assertThat(data(booked).path("source").asText()).isEqualTo("PHONE");
        assertThat(data(booked).path("otaChannel").isNull()).isTrue();
        assertThat(data(booked).path("otaReference").asText()).isEqualTo("call-77");
    }

    @Test
    void ota_without_channel_is_400() {
        String token = adminToken();
        UUID room = newRoom(token);

        ResponseEntity<String> response = book(token, room, 0, Map.of("source", "OTA"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_SOURCE");
        assertThat(reservationCount(room)).as("nothing was booked").isZero();
    }

    @Test
    void channel_without_ota_is_400() {
        String token = adminToken();
        UUID room = newRoom(token);

        ResponseEntity<String> implicit = book(token, room, 0, Map.of("otaChannel", "AGODA"));
        ResponseEntity<String> phone = book(token, room, 0, Map.of("source", "PHONE", "otaChannel", "BOOKING_COM"));

        for (ResponseEntity<String> response : List.of(implicit, phone)) {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_SOURCE");
        }
        assertThat(reservationCount(room)).as("nothing was booked").isZero();
    }

    @Test
    void unknown_channel_is_400() {
        String token = adminToken();
        UUID room = newRoom(token);

        ResponseEntity<String> response = book(token, room, 0, Map.of("source", "OTA", "otaChannel", "NOPE"));
        ResponseEntity<String> badSource = book(token, room, 0, Map.of("source", "CARRIER_PIGEON"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(badSource.getStatusCode().value()).as(badSource.getBody()).isEqualTo(400);
        assertThat(reservationCount(room)).as("nothing was booked").isZero();
    }

    private UUID newRoom(String token) {
        int n = SEQUENCE.incrementAndGet();
        String tag = Integer.toString(n + 1000 * (int) (System.nanoTime() % 1000));
        UUID type = newRoomType(token, "BS" + tag, 2, 900_000, true);
        return newRoom(token, "BS" + tag, type, true);
    }

    private ResponseEntity<String> book(String token, UUID room, int offsetDays, Map<String, Object> extra) {
        LocalDate in = LocalDate.now().plusDays(40L + offsetDays);
        Map<String, Object> body = reservationBody(
            null, inlineGuest("Source Guest " + SEQUENCE.incrementAndGet(), "0912000000"), room, in, in.plusDays(2), 1);
        body.putAll(extra);
        return post(RESERVATIONS, token, body);
    }

    private long reservationCount(UUID room) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM hotel.reservation WHERE room_id = ?", Long.class, room);
        return count == null ? 0 : count;
    }
}
