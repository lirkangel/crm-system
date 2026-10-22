package com.crm.foundation.hotel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** No double booking under concurrency: the database, not timing, decides who gets the room. */
class ReservationConcurrencyIT extends HotelITSupport {

    private static final LocalDate IN = LocalDate.now().plusDays(20);
    private static final String RESERVATIONS = HOTEL_API + "/reservations";
    private static final int BOOKERS = 8;

    @Autowired
    private DataSource dataSource;

    @Test
    void parallel_bookings_one_wins() throws Exception {
        String token = adminToken();
        String tag = tag();
        UUID room = newRoom(token, "PB" + tag, newRoomType(token, "PB" + tag, 2, 1_000_000, true), true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(BOOKERS);
        List<ResponseEntity<String>> responses = new ArrayList<>();
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < BOOKERS; i++) {
                String guest = "Racer " + tag + " " + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return post(RESERVATIONS, token,
                        reservationBody(null, inlineGuest(guest, "0900000000"), room,
                            IN, IN.plusDays(2), 1));
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<ResponseEntity<String>> created = responses.stream().filter(r -> r.getStatusCode().value() == 201).toList();
        List<ResponseEntity<String>> rejected = responses.stream().filter(r -> r.getStatusCode().value() != 201).toList();
        assertThat(created).as(bodies(responses)).hasSize(1);
        assertThat(rejected).as(bodies(responses)).hasSize(BOOKERS - 1).allSatisfy(response -> {
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        });
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.reservation WHERE status = 'CONFIRMED' AND room_id = ?", Integer.class, room))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.guest WHERE full_name LIKE ?", Integer.class, "Racer " + tag + " %"))
            .as("only the winner's inline guest is kept").isEqualTo(1);
    }

    /**
     * A conflicting row the application's pre-check cannot see (inserted by a transaction that is
     * still open) makes the booking's insert wait on the exclusion constraint; once that transaction
     * commits, the violation must come back as 409 {@code HOTEL_ROOM_UNAVAILABLE}, not a 500.
     */
    @Test
    void exclusion_constraint_violation_is_409_unavailable() throws Exception {
        String token = adminToken();
        String tag = tag();
        UUID room = newRoom(token, "EX" + tag, newRoomType(token, "EX" + tag, 2, 1_000_000, true), true);

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            UUID guestId = UUID.randomUUID();
            try (PreparedStatement guest = other.prepareStatement(
                    "INSERT INTO hotel.guest (id, full_name, phone, search_key, version) VALUES (?, ?, '0900', ?, 0)")) {
                guest.setObject(1, guestId);
                guest.setString(2, "Hidden " + tag);
                guest.setString(3, "hidden " + tag + " 0900");
                guest.executeUpdate();
            }
            try (PreparedStatement reservation = other.prepareStatement("""
                    INSERT INTO hotel.reservation (id, code, guest_id, room_id, check_in, check_out, adults, status,
                                                   nightly_rate_gross_vnd, total_gross_vnd, version)
                    VALUES (?, ?, ?, ?, ?, ?, 1, 'CONFIRMED', 1, 1, 0)
                    """)) {
                reservation.setObject(1, UUID.randomUUID());
                reservation.setString(2, "HID-" + tag);
                reservation.setObject(3, guestId);
                reservation.setObject(4, room);
                reservation.setObject(5, IN);
                reservation.setObject(6, IN.plusDays(3));
                reservation.executeUpdate();
            }

            CompletableFuture<ResponseEntity<String>> booking = CompletableFuture.supplyAsync(() -> post(
                RESERVATIONS, token,
                reservationBody(null, inlineGuest("Blocked " + tag, "0901"), room, IN.plusDays(1), IN.plusDays(2), 1)));
            awaitBlockedOnLock(booking);
            other.commit();

            ResponseEntity<String> response = booking.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hotel.guest WHERE full_name = ?", Integer.class, "Blocked " + tag)).isZero();
    }

    /** Waits until some backend waits for a lock: the booking stuck behind the open transaction's row. */
    private void awaitBlockedOnLock(CompletableFuture<ResponseEntity<String>> booking) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertThat(booking).as("booking must wait for the open transaction, not finish").isNotDone();
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("booking never blocked on the uncommitted conflicting reservation");
    }

    private static String bodies(List<ResponseEntity<String>> responses) {
        return responses.stream().map(r -> r.getStatusCode().value() + " " + r.getBody()).toList().toString();
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
