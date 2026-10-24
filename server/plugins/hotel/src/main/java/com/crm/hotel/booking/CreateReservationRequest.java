package com.crm.hotel.booking;

import com.crm.hotel.domain.BookingSource;
import com.crm.hotel.domain.OtaChannel;
import com.crm.hotel.guest.GuestRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Body of {@code POST /reservations}: exactly one of {@code guestId} (an existing guest) or
 * {@code newGuest} (created with the booking). Dates and party size are checked by {@link StayPolicy}.
 * {@code source} defaults to {@code WALK_IN}; {@code otaChannel} is required for {@code OTA} and forbidden otherwise;
 * {@code otaReference} (the channel's booking number) is allowed with any source.
 */
public record CreateReservationRequest(
        UUID guestId,
        @Valid GuestRequest newGuest,
        @NotNull UUID roomId,
        LocalDate checkIn,
        LocalDate checkOut,
        int adults,
        @Size(max = 2000) String notes,
        BookingSource source,
        OtaChannel otaChannel,
        @Size(max = 64) String otaReference) {
}
