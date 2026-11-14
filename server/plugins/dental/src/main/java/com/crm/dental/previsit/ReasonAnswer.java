package com.crm.dental.previsit;

/** Why the patient comes: a code, plus a text that {@code OTHER} requires. Health data: never printed. */
public record ReasonAnswer(ReasonCode code, String text) {

    @Override
    public String toString() {
        return "ReasonAnswer[redacted]";
    }
}
