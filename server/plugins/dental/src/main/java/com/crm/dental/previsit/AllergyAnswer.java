package com.crm.dental.previsit;

import java.util.Set;

/**
 * The allergy question: exactly one of {@code none} or a non-empty {@code items} selection ({@code OTHER} needs
 * {@code otherText}). Health data: never printed.
 */
public record AllergyAnswer(boolean none, Set<AllergyCode> items, String otherText) {

    @Override
    public String toString() {
        return "AllergyAnswer[redacted]";
    }
}
