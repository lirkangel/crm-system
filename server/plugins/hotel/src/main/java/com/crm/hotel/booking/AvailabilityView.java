package com.crm.hotel.booking;

import java.time.LocalDate;
import java.util.List;

/** Free rooms for a stay, grouped by room type (types without a free room are absent). */
public record AvailabilityView(
        LocalDate checkIn,
        LocalDate checkOut,
        int nights,
        List<RoomTypeAvailability> roomTypes) {
}
