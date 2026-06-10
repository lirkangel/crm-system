package com.crm.foundation.DTO;

import com.crm.foundation.Domain.ChangeEvent;

import java.time.Instant;
import java.util.UUID;

/** Explicit response shape for the delta feed (T012) — decoupled from the entity. */
public record ChangeEventResponse(
    UUID id,
    String pluginId,
    String entityType,
    UUID entityId,
    Long version,
    String op,
    Instant occurredAt
) {
    public static ChangeEventResponse from(ChangeEvent e) {
        return new ChangeEventResponse(
            e.getId(),
            e.getPluginId(),
            e.getEntityType(),
            e.getEntityId(),
            e.getVersion(),
            e.getOp(),
            e.getOccurredAt());
    }
}
