package com.crm.dental.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code POST /patients} and {@code PUT /patients/{id}} (also the {@code newPatient} of a booking). Name,
 * phone and date of birth are required; the phone is normalised and the birth date checked by the service.
 */
public record PatientRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Size(max = 32) String phone,
        @NotNull LocalDate dateOfBirth,
        @Size(max = 16) String idType,
        @Size(max = 64) String idNumber,
        @Size(max = 2) String nationality,
        @Size(max = 500) String address) {
}
