package com.crm.hotel.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A room type with free rooms for the stay, its nightly rate and the stay total (both gross VND). */
public record RoomTypeAvailability(
        UUID roomTypeId,
        String code,
        Map<String, String> nameI18n,
        long nightlyRateGrossVnd,
        long totalGrossVnd,
        List<AvailableRoom> rooms) {
}
