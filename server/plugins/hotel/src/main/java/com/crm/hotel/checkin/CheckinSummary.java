package com.crm.hotel.checkin;

import com.crm.hotel.stay.IdDocType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the guest sees of the stay behind their link. No identity values: submitted guests show a masked name only.
 * {@code open} is false once the reservation is no longer {@code CONFIRMED} (the page then reads, not writes).
 */
public record CheckinSummary(
        String hotelName,
        String roomTypeName,
        LocalDate checkIn,
        LocalDate checkOut,
        int adults,
        int maxGuests,
        boolean open,
        List<SubmittedGuest> guests) {

    /** One identity the guest already sent. */
    public record SubmittedGuest(UUID id, String maskedName, IdDocType documentType) {
    }
}
