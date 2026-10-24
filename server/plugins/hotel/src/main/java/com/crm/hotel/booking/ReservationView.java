package com.crm.hotel.booking;

import com.crm.hotel.domain.Reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A reservation as served by the API (amounts in whole VND, gross). */
public record ReservationView(
        UUID id,
        String code,
        UUID guestId,
        String guestName,
        UUID roomId,
        String roomNumber,
        LocalDate checkIn,
        LocalDate checkOut,
        int nights,
        int adults,
        String status,
        long nightlyRateGrossVnd,
        long totalGrossVnd,
        String notes,
        Instant createdAt,
        Instant cancelledAt,
        String source,
        String otaChannel,
        String otaReference,
        Instant checkedInAt,
        Instant checkedOutAt,
        long version) {

    static ReservationView of(Reservation reservation, String guestName, String roomNumber) {
        return new ReservationView(
            reservation.getId(), reservation.getCode(), reservation.getGuestId(), guestName,
            reservation.getRoomId(), roomNumber, reservation.getCheckIn(), reservation.getCheckOut(),
            StayPolicy.nights(reservation.getCheckIn(), reservation.getCheckOut()), reservation.getAdults(),
            reservation.getStatus().name(), reservation.getNightlyRateGrossVnd(), reservation.getTotalGrossVnd(),
            reservation.getNotes(), reservation.getCreatedAt(), reservation.getCancelledAt(),
            reservation.getSource().name(), reservation.getOtaChannel() == null ? null : reservation.getOtaChannel().name(),
            reservation.getOtaReference(), reservation.getCheckedInAt(), reservation.getCheckedOutAt(),
            reservation.getVersion());
    }

    /** A {@code [Reservation, guest full name, room number]} row of the repository's detailed queries. */
    static ReservationView of(Object[] row) {
        return of((Reservation) row[0], (String) row[1], (String) row[2]);
    }
}
