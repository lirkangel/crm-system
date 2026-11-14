package com.crm.dental.previsit;

import java.time.LocalDate;

/** The returning-patient check: the patient's date of birth. */
public record PreviousRequest(LocalDate dateOfBirth) {

    @Override
    public String toString() {
        return "PreviousRequest[redacted]";
    }
}
