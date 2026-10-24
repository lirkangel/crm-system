package com.crm.hotel.stay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /reservations/{id}/check-in}: the adults staying (1..the room type's {@code maxGuests}) and,
 * for a room swap, the room to move to (same room type, in service); null keeps the booked room.
 */
public record CheckInRequest(
        @NotEmpty @Valid List<@NotNull StayGuestRequest> adults,
        UUID roomId) {
}
