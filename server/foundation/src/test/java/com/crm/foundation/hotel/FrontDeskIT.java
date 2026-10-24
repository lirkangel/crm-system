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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The front-desk lists: arrivals, in-house and departures, with the late/overdue flags that keep late arrivals and
 * overstayers from vanishing. Other ITs share the database, so each test names its rooms with a unique prefix and
 * looks only at those rows.
 */
class FrontDeskIT extends HotelITSupport {

    private static final String FRONT_DESK = HOTEL_API + "/front-desk";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void arrivals_today_and_late_flagged() {
        String token = adminToken();
        String prefix = prefix();
        LocalDate date = LocalDate.now().plusDays(300);
        UUID type = newRoomType(token, prefix, 2, 1_000_000, true);
        UUID arrivesToday = seededRoom(token, prefix + "1", type);
        UUID arrivedEarlier = seededRoom(token, prefix + "2", type);
        UUID arrivesTomorrow = seededRoom(token, prefix + "3", type);
        UUID leavesToday = seededRoom(token, prefix + "4", type);
        UUID arrivalId = seedReservation(arrivesToday, date, date.plusDays(2), "CONFIRMED");
        seedReservation(arrivedEarlier, date.minusDays(2), date.plusDays(1), "CONFIRMED");
        seedReservation(arrivesTomorrow, date.plusDays(1), date.plusDays(3), "CONFIRMED");
        seedReservation(leavesToday, date.minusDays(3), date, "CONFIRMED");

        ResponseEntity<String> response = get(FRONT_DESK + "/arrivals?date=" + date, token);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<JsonNode> mine = mine(response, prefix);
        assertThat(mine.stream().map(e -> e.path("reservation").path("roomNumber").asText()).toList())
            .as("ordered by room number; checkIn <= date < checkOut only")
            .containsExactly(prefix + "1", prefix + "2");
        assertThat(mine.stream().map(e -> e.path("late").asBoolean()).toList()).containsExactly(false, true);
        assertThat(mine.stream().map(e -> e.path("overdue").asBoolean()).toList()).containsExactly(false, false);
        JsonNode first = mine.get(0).path("reservation");
        assertThat(first.path("id").asText()).isEqualTo(arrivalId.toString());
        assertThat(first.path("guestName").asText()).startsWith("Seed Guest ");
        assertThat(first.path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void cancelled_and_checked_in_not_in_arrivals() {
        String token = adminToken();
        String prefix = prefix();
        LocalDate date = LocalDate.now().plusDays(310);
        UUID type = newRoomType(token, prefix, 2, 1_000_000, true);
        UUID confirmed = seededRoom(token, prefix + "1", type);
        UUID cancelled = seededRoom(token, prefix + "2", type);
        UUID checkedIn = seededRoom(token, prefix + "3", type);
        UUID checkedOut = seededRoom(token, prefix + "4", type);
        UUID confirmedId = seedReservation(confirmed, date, date.plusDays(1), "CONFIRMED");
        seedReservation(cancelled, date, date.plusDays(1), "CANCELLED");
        seedReservation(checkedIn, date, date.plusDays(1), "CHECKED_IN");
        seedReservation(checkedOut, date, date.plusDays(1), "CHECKED_OUT");

        List<JsonNode> mine = mine(get(FRONT_DESK + "/arrivals?date=" + date, token), prefix);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("reservation").path("id").asText()).isEqualTo(confirmedId.toString());
    }

    @Test
    void in_house_flags_overstayer() {
        String token = adminToken();
        String prefix = prefix();
        LocalDate today = hotelToday();
        UUID type = newRoomType(token, prefix, 2, 1_000_000, true);
        UUID overstayer = seededRoom(token, prefix + "1", type);
        UUID leavesToday = seededRoom(token, prefix + "2", type);
        UUID staying = seededRoom(token, prefix + "3", type);
        UUID notArrived = seededRoom(token, prefix + "4", type);
        UUID gone = seededRoom(token, prefix + "5", type);
        UUID overstayerId = seedReservation(overstayer, today.minusDays(5), today.minusDays(2), "CHECKED_IN");
        seedReservation(leavesToday, today.minusDays(1), today, "CHECKED_IN");
        seedReservation(staying, today, today.plusDays(2), "CHECKED_IN");
        seedReservation(notArrived, today, today.plusDays(2), "CONFIRMED");
        seedReservation(gone, today.minusDays(3), today.minusDays(1), "CHECKED_OUT");

        ResponseEntity<String> response = get(FRONT_DESK + "/in-house", token);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<JsonNode> mine = mine(response, prefix);
        assertThat(mine.stream().map(e -> e.path("reservation").path("roomNumber").asText()).toList())
            .containsExactly(prefix + "1", prefix + "2", prefix + "3");
        assertThat(mine.stream().map(e -> e.path("overdue").asBoolean()).toList())
            .as("only checkOut < today is overdue").containsExactly(true, false, false);
        assertThat(mine.stream().map(e -> e.path("late").asBoolean()).toList()).containsExactly(false, false, false);
        assertThat(mine.get(0).path("reservation").path("id").asText()).isEqualTo(overstayerId.toString());
    }

    @Test
    void departures_include_overdue() {
        String token = adminToken();
        String prefix = prefix();
        LocalDate date = LocalDate.now().plusDays(320);
        UUID type = newRoomType(token, prefix, 2, 1_000_000, true);
        UUID leavesOnDate = seededRoom(token, prefix + "1", type);
        UUID overstayer = seededRoom(token, prefix + "2", type);
        UUID leavesLater = seededRoom(token, prefix + "3", type);
        UUID confirmed = seededRoom(token, prefix + "4", type);
        seedReservation(leavesOnDate, date.minusDays(2), date, "CHECKED_IN");
        UUID overstayerId = seedReservation(overstayer, date.minusDays(6), date.minusDays(3), "CHECKED_IN");
        seedReservation(leavesLater, date.minusDays(1), date.plusDays(1), "CHECKED_IN");
        seedReservation(confirmed, date.minusDays(1), date, "CONFIRMED");

        ResponseEntity<String> response = get(FRONT_DESK + "/departures?date=" + date, token);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<JsonNode> mine = mine(response, prefix);
        assertThat(mine.stream().map(e -> e.path("reservation").path("roomNumber").asText()).toList())
            .containsExactly(prefix + "1", prefix + "2");
        assertThat(mine.stream().map(e -> e.path("overdue").asBoolean()).toList()).containsExactly(false, true);
        assertThat(mine.get(1).path("reservation").path("id").asText()).isEqualTo(overstayerId.toString());

        // one day later the stay that was leaving later is due too, and the earlier ones are all overdue
        List<JsonNode> nextDay = mine(get(FRONT_DESK + "/departures?date=" + date.plusDays(1), token), prefix);
        assertThat(nextDay.stream().map(e -> e.path("overdue").asBoolean()).toList())
            .containsExactly(true, true, false);
    }

    @Test
    void default_date_is_hotel_today() {
        String token = adminToken();
        String prefix = prefix();
        LocalDate today = hotelToday();
        UUID type = newRoomType(token, prefix, 2, 1_000_000, true);
        UUID arrivesToday = seededRoom(token, prefix + "1", type);
        UUID arrivedYesterday = seededRoom(token, prefix + "2", type);
        UUID arrivesTomorrow = seededRoom(token, prefix + "3", type);
        UUID leavesToday = seededRoom(token, prefix + "4", type);
        UUID overstayer = seededRoom(token, prefix + "5", type);
        UUID leavesTomorrow = seededRoom(token, prefix + "6", type);
        seedReservation(arrivesToday, today, today.plusDays(1), "CONFIRMED");
        seedReservation(arrivedYesterday, today.minusDays(1), today.plusDays(1), "CONFIRMED");
        seedReservation(arrivesTomorrow, today.plusDays(1), today.plusDays(2), "CONFIRMED");
        seedReservation(leavesToday, today.minusDays(2), today, "CHECKED_IN");
        seedReservation(overstayer, today.minusDays(4), today.minusDays(1), "CHECKED_IN");
        seedReservation(leavesTomorrow, today.minusDays(1), today.plusDays(1), "CHECKED_IN");

        List<JsonNode> arrivals = mine(get(FRONT_DESK + "/arrivals", token), prefix);
        List<JsonNode> departures = mine(get(FRONT_DESK + "/departures", token), prefix);
        List<JsonNode> blank = mine(get(FRONT_DESK + "/arrivals?date=", token), prefix);

        assertThat(arrivals.stream().map(e -> e.path("reservation").path("roomNumber").asText()).toList())
            .containsExactly(prefix + "1", prefix + "2");
        assertThat(arrivals.stream().map(e -> e.path("late").asBoolean()).toList()).containsExactly(false, true);
        assertThat(blank).as("a blank date means the default").hasSize(2);
        assertThat(departures.stream().map(e -> e.path("reservation").path("roomNumber").asText()).toList())
            .containsExactly(prefix + "4", prefix + "5");
        assertThat(departures.stream().map(e -> e.path("overdue").asBoolean()).toList()).containsExactly(false, true);
    }

    @Test
    void bad_date_is_400() {
        String token = adminToken();

        for (String path : List.of(
                "/arrivals?date=tomorrow", "/arrivals?date=2026-13-45", "/departures?date=31/12/2026",
                "/departures?date=20261231")) {
            ResponseEntity<String> response = get(FRONT_DESK + path, token);
            assertThat(response.getStatusCode().value()).as(path + " " + response.getBody()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).as(path).isEqualTo("HOTEL_INVALID_QUERY");
        }
    }

    @Test
    void lists_need_reservations_read() {
        String reader = tokenWith("hotel_it_frontdesk_reader", "hotel.reservations.read");
        String other = tokenWith("hotel_it_frontdesk_other", "hotel.stays.read");

        for (String path : List.of("/arrivals", "/in-house", "/departures")) {
            assertThat(get(FRONT_DESK + path, reader).getStatusCode().value()).as(path).isEqualTo(200);
            assertThat(get(FRONT_DESK + path, other).getStatusCode().value()).as(path).isEqualTo(403);
            assertThat(get(FRONT_DESK + path, null).getStatusCode().value()).as(path).isEqualTo(401);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private UUID seededRoom(String token, String number, UUID type) {
        return newRoom(token, number, type, true);
    }

    /** The list's entries whose room number starts with {@code prefix}, in the order served. */
    private List<JsonNode> mine(ResponseEntity<String> response, String prefix) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<JsonNode> mine = new ArrayList<>();
        for (JsonNode entry : data(response)) {
            if (entry.path("reservation").path("roomNumber").asText().startsWith(prefix)) {
                mine.add(entry);
            }
        }
        return mine;
    }

    /** Five hex characters (room numbers are at most 16 characters; the tests append one or two). */
    private static String prefix() {
        return "F" + UUID.randomUUID().toString().substring(0, 5);
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
}
