package com.crm.hotel.stay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One adult's identity record at check-in. Rules across fields ({@link CheckInPolicy#checkIdentity}): a Vietnamese
 * citizen ({@code nationality = VN}) needs an address, and {@code entryDate} is not after the hotel-local calendar
 * date.
 */
public record StayGuestRequest(
        @NotNull IdDocType idType,
        @NotBlank @Size(max = 32) String idNumber,
        @NotBlank @Size(max = 200) String fullName,
        @NotNull @Past LocalDate dateOfBirth,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String nationality,
        @Size(max = 500) String address,
        @Size(max = 32) String visaNumber,
        LocalDate entryDate,
        /** A scan uploaded for this reservation ({@code POST /id-scans}) to keep with the identity record. */
        UUID idScanId) {

    /** Without a scan. */
    public StayGuestRequest(IdDocType idType, String idNumber, String fullName, LocalDate dateOfBirth,
            String nationality, String address, String visaNumber, LocalDate entryDate) {
        this(idType, idNumber, fullName, dateOfBirth, nationality, address, visaNumber, entryDate, null);
    }
}
