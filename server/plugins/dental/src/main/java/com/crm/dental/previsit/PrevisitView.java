package com.crm.dental.previsit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A submitted form as served to staff holding {@code dental.previsit.read}, health answers decrypted.
 * {@code identity} is null for a child's form without identity details. {@code patientVersion} is the patient
 * record's version, the {@code If-Match} of the apply call. Identity and health values: never printed.
 */
public record PrevisitView(
        UUID appointmentId,
        UUID patientId,
        long patientVersion,
        PrevisitRequest.Identity identity,
        String parentName,
        String parentPhone,
        String phone,
        AllergyAnswer allergies,
        MedicineAnswer medicines,
        ReasonAnswer reason,
        boolean hasIdScan,
        List<String> editedFields,
        Instant consentAt,
        String noticeVersion,
        Instant submittedAt) {

    @Override
    public String toString() {
        return "PrevisitView[redacted]";
    }
}
