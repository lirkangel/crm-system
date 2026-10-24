package com.crm.hotel.stay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * One adult's identity record at check-in. Rules across fields ({@link CheckInPolicy#checkIdentity}): a Vietnamese
 * citizen ({@code nationality = VN}) needs an address, and {@code entryDate} is not after today.
 */
public record StayGuestRequest(
        @NotNull IdDocType idType,
        @NotBlank @Size(max = 32) String idNumber,
        @NotBlank @Size(max = 200) String fullName,
        @NotNull @Past LocalDate dateOfBirth,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String nationality,
        @Size(max = 500) String address,
        @Size(max = 32) String visaNumber,
        LocalDate entryDate) {
}
