package com.crm.dental.previsit;

/**
 * Earlier health answers, shown back only after the date of birth matched. Never identity.
 * {@code fromCurrentAppointment} is true when they are this appointment's own submission.
 */
public record PreviousAnswers(
        AllergyAnswer allergies,
        MedicineAnswer medicines,
        ReasonAnswer reason,
        boolean fromCurrentAppointment) {
}
