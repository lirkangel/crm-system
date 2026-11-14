package com.crm.dental.appointment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** Body of {@code PATCH /appointments/{id}}: the new time; a missing duration or dentist is left as it is. */
public record RescheduleRequest(
        @NotNull OffsetDateTime startsAt,
        Integer durationMinutes,
        @Size(max = 200) String dentist) {
}
