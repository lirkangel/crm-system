package com.crm.hotel.stay;

import java.time.LocalDate;
import java.util.UUID;

/** An identity record as served to holders of {@code hotel.stays.read}. */
public record StayGuestView(
        UUID id,
        String idType,
        String idNumber,
        String fullName,
        LocalDate dateOfBirth,
        String nationality,
        String address,
        String visaNumber,
        LocalDate entryDate,
        long version) {

    static StayGuestView of(StayGuest guest) {
        return new StayGuestView(guest.getId(), guest.getIdType().name(), guest.getIdNumber(), guest.getFullName(),
            guest.getDateOfBirth(), guest.getNationality(), guest.getAddress(), guest.getVisaNumber(),
            guest.getEntryDate(), guest.getVersion());
    }
}
