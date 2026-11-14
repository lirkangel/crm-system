package com.crm.dental.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A patient as served to staff with {@code dental.patients.read}. */
public record PatientView(
        UUID id,
        String fullName,
        String phone,
        LocalDate dateOfBirth,
        String idType,
        String idNumber,
        String nationality,
        String address,
        Instant createdAt,
        long version) {

    public static PatientView of(Patient patient) {
        return new PatientView(patient.getId(), patient.getFullName(), patient.getPhone(), patient.getDateOfBirth(),
            patient.getIdType(), patient.getIdNumber(), patient.getNationality(), patient.getAddress(),
            patient.getCreatedAt(), patient.getVersion());
    }
}
