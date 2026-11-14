package com.crm.dental.previsit;

/** The medicines question: exactly one of {@code none} or a non-blank {@code text}. Health data: never printed. */
public record MedicineAnswer(boolean none, String text) {

    @Override
    public String toString() {
        return "MedicineAnswer[redacted]";
    }
}
