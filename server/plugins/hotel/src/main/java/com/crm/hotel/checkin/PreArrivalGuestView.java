package com.crm.hotel.checkin;

import com.crm.hotel.stay.IdDocType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A guest's pre-arrival submission as staff see it ({@code hotel.stays.read}): every field of a check-in identity
 * record, plus the scan, the fields the guest changed after the scan filled them in, and consent. Personal data.
 */
public record PreArrivalGuestView(
        UUID id,
        IdDocType idType,
        String idNumber,
        String fullName,
        LocalDate dateOfBirth,
        String nationality,
        String address,
        String visaNumber,
        LocalDate entryDate,
        UUID idScanId,
        List<String> editedFields,
        Instant consentAt,
        Instant submittedAt) {

    static PreArrivalGuestView of(PreArrivalGuest guest) {
        String edited = guest.getEditedFields();
        List<String> fields = edited == null || edited.isBlank() ? List.of() : Arrays.asList(edited.split(","));
        return new PreArrivalGuestView(guest.getId(), guest.getIdType(), guest.getIdNumber(), guest.getFullName(),
            guest.getDateOfBirth(), guest.getNationality(), guest.getAddress(), guest.getVisaNumber(),
            guest.getEntryDate(), guest.getIdScanId(), fields, guest.getConsentAt(), guest.getSubmittedAt());
    }
}
