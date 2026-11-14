package com.crm.dental.previsit;

import java.time.OffsetDateTime;

/**
 * What a patient's link shows before anything is entered: no identity or health values, only the masked name.
 * {@code child} (under 14 on the visit's clinic-local date) tells the page which path to offer.
 */
public record VisitSummary(
        String clinicName,
        OffsetDateTime startsAt,
        int durationMinutes,
        String dentist,
        String maskedName,
        boolean open,
        boolean submitted,
        Returning returning,
        boolean child) {

    /** {@code NONE}: no earlier answers; {@code AVAILABLE}: the date-of-birth check can be offered; {@code LOCKED}: 5 wrong dates. */
    public enum Returning {
        NONE, AVAILABLE, LOCKED
    }
}
