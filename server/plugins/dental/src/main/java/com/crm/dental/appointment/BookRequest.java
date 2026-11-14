package com.crm.dental.appointment;

import com.crm.dental.patient.PatientRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Body of {@code POST /appointments}: exactly one of {@code patientId} (an existing patient) or
 * {@code newPatient} (created with the booking). {@code durationMinutes} defaults to 30.
 */
public record BookRequest(
        UUID patientId,
        @Valid PatientRequest newPatient,
        @NotNull OffsetDateTime startsAt,
        Integer durationMinutes,
        @Size(max = 200) String dentist,
        @Size(max = 500) String reason) {
}
