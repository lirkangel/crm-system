package com.crm.dental.previsit;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The pre-visit form as a patient sends it. Identity is the hotel's minus visa and entry date; it is optional for a
 * child under 14, who needs {@code parentName} and {@code parentPhone} instead (rejected for an adult). Rules are
 * checked in {@link PrevisitRules}, under the appointment lock. Personal and health data: never printed.
 */
public record PrevisitRequest(
        Identity identity,
        String parentName,
        String parentPhone,
        String phone,
        AllergyAnswer allergies,
        MedicineAnswer medicines,
        ReasonAnswer reason,
        boolean consent,
        UUID idScanId) {

    /** {@code idType} is {@code CCCD}, {@code PASSPORT} or {@code OTHER}; {@code nationality} is ISO alpha-2. */
    public record Identity(
            String idType,
            String idNumber,
            String fullName,
            LocalDate dateOfBirth,
            String nationality,
            String address) {

        @Override
        public String toString() {
            return "Identity[redacted]";
        }
    }

    @Override
    public String toString() {
        return "PrevisitRequest[redacted]";
    }
}
