package com.crm.foundation.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One whole stay over HTTP, the way a front desk lives it: an OTA booking appears in arrivals, two adults are checked
 * in, the OTA prepayment covers the room, a minibar charge is settled in cash, the guest shows up in departures on
 * the check-out date, check-out succeeds at zero balance and the same room can be booked again for the same dates.
 */
class FullStayIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";
    private static final String FRONT_DESK = HOTEL_API + "/front-desk";

    @Test
    void ota_guest_full_stay() {
        String token = adminToken();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        UUID room = newRoom(token, "FS" + tag, newRoomType(token, "FS" + tag, 2, 1_000_000, true), true);
        LocalDate today = hotelToday();
        LocalDate checkOut = today.plusDays(2);
        long roomTotal = 2_000_000L;

        // 1. OTA booking (Agoda) for today..+2
        Map<String, Object> booking = reservationBody(
            null, inlineGuest("Full Stay " + tag, "0988" + (100_000 + (int) (Math.random() * 899_999))),
            room, today, checkOut, 2);
        booking.put("source", "OTA");
        booking.put("otaChannel", "AGODA");
        booking.put("otaReference", "AG-" + tag);
        ResponseEntity<String> booked = post(RESERVATIONS, token, booking);
        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        JsonNode bookedView = data(booked);
        UUID id = UUID.fromString(bookedView.path("id").asText());
        assertThat(bookedView.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(bookedView.path("source").asText()).isEqualTo("OTA");
        assertThat(bookedView.path("otaChannel").asText()).isEqualTo("AGODA");
        assertThat(bookedView.path("otaReference").asText()).isEqualTo("AG-" + tag);
        assertThat(bookedView.path("totalGrossVnd").asLong()).isEqualTo(roomTotal);

        // 2. appears in today's arrivals
        ResponseEntity<String> arrivals = get(FRONT_DESK + "/arrivals", token);
        assertThat(arrivals.getStatusCode().value()).as(arrivals.getBody()).isEqualTo(200);
        assertThat(entryFor(arrivals, id)).as("arrivals lists the OTA booking").isNotNull();
        assertThat(entryFor(arrivals, id).path("late").asBoolean()).isFalse();

        // 3. check in two adults
        Map<String, Object> checkIn = new LinkedHashMap<>();
        checkIn.put("adults", List.of(
            adult("CCCD", "0790" + (10_000_000 + (long) (Math.random() * 89_000_000)), "Full Stay A " + tag),
            adult("CCCD", "0791" + (10_000_000 + (long) (Math.random() * 89_000_000)), "Full Stay B " + tag)));
        ResponseEntity<String> checkedIn = post(RESERVATIONS + "/" + id + "/check-in", token, checkIn);
        assertThat(checkedIn.getStatusCode().value()).as(checkedIn.getBody()).isEqualTo(200);
        assertThat(data(checkedIn).path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(Instant.parse(data(checkedIn).path("checkedInAt").textValue())).isNotNull();

        // 4. in-house now, no longer an arrival
        ResponseEntity<String> inHouse = get(FRONT_DESK + "/in-house", token);
        assertThat(inHouse.getStatusCode().value()).as(inHouse.getBody()).isEqualTo(200);
        assertThat(entryFor(inHouse, id)).as("in-house lists the stay").isNotNull();
        assertThat(entryFor(inHouse, id).path("overdue").asBoolean()).isFalse();
        assertThat(entryFor(get(FRONT_DESK + "/arrivals", token), id)).isNull();

        // 5. OTA prepayment for the room total
        Map<String, Object> ota = new LinkedHashMap<>();
        ota.put("method", "OTA");
        ota.put("otaChannel", "AGODA");
        ota.put("amountGrossVnd", roomTotal);
        ResponseEntity<String> prepaid = post(RESERVATIONS + "/" + id + "/folio/payments", token, ota);
        assertThat(prepaid.getStatusCode().value()).as(prepaid.getBody()).isEqualTo(201);
        assertThat(data(prepaid).path("balanceGrossVnd").asLong()).isZero();

        // 6. minibar charge
        Map<String, Object> minibar = new LinkedHashMap<>();
        minibar.put("description", "Minibar");
        minibar.put("amountGrossVnd", 85_000L);
        ResponseEntity<String> charged = post(RESERVATIONS + "/" + id + "/folio/charges", token, minibar);
        assertThat(charged.getStatusCode().value()).as(charged.getBody()).isEqualTo(201);
        assertThat(data(charged).path("balanceGrossVnd").asLong()).isEqualTo(85_000L);

        // 7. check-out is refused while the minibar is unpaid
        ResponseEntity<String> early = post(RESERVATIONS + "/" + id + "/check-out", token, null);
        assertThat(early.getStatusCode().value()).as(early.getBody()).isEqualTo(409);
        assertThat(json(early).path("code").asText()).isEqualTo("HOTEL_BALANCE_DUE");

        // 8. cash for the minibar
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("method", "CASH");
        cash.put("amountGrossVnd", 85_000L);
        ResponseEntity<String> settled = post(RESERVATIONS + "/" + id + "/folio/payments", token, cash);
        assertThat(settled.getStatusCode().value()).as(settled.getBody()).isEqualTo(201);
        assertThat(data(settled).path("balanceGrossVnd").asLong()).isZero();

        // 9. departures on the check-out date
        ResponseEntity<String> departures = get(FRONT_DESK + "/departures?date=" + checkOut, token);
        assertThat(departures.getStatusCode().value()).as(departures.getBody()).isEqualTo(200);
        assertThat(entryFor(departures, id)).as("departures on the check-out date lists the stay").isNotNull();

        // 10. check out
        ResponseEntity<String> out = post(RESERVATIONS + "/" + id + "/check-out", token, null);
        assertThat(out.getStatusCode().value()).as(out.getBody()).isEqualTo(200);
        assertThat(data(out).path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(Instant.parse(data(out).path("checkedOutAt").textValue())).isNotNull();
        assertThat(entryFor(get(FRONT_DESK + "/in-house", token), id)).isNull();
        assertThat(entryFor(get(FRONT_DESK + "/departures?date=" + checkOut, token), id)).isNull();

        // 11. the room is bookable again for the same dates
        ResponseEntity<String> rebooked = post(RESERVATIONS, token, reservationBody(
            null, inlineGuest("Next Guest " + tag, "0977123456"), room, today, checkOut, 2));
        assertThat(rebooked.getStatusCode().value()).as(rebooked.getBody()).isEqualTo(201);
    }

    /** The list entry whose reservation has {@code id}, or {@code null}. */
    private JsonNode entryFor(ResponseEntity<String> list, UUID id) {
        assertThat(list.getStatusCode().value()).as(list.getBody()).isEqualTo(200);
        return StreamSupport.stream(data(list).spliterator(), false)
            .filter(entry -> id.toString().equals(entry.path("reservation").path("id").asText()))
            .findFirst().orElse(null);
    }
}
