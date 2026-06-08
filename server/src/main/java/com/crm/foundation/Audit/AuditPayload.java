package com.crm.foundation.Audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * The hash-chained audit envelope. Mirrors {@code audit_event} columns (spec §12.1).
 * <p>
 * {@code severity} is excluded from the chained hash (spec §12.2 lists the hashed
 * fields and severity is not one of them) so it can be classified without affecting
 * the chain's integrity contract.
 */
@JsonIgnoreProperties({"severity"})
public record AuditPayload(
    Instant occurredAt,
    UUID userId,
    String sourceIp,
    String pluginId,
    String entityType,
    UUID entityId,
    String op,
    String beforeJson,
    String afterJson,
    String severity
) {
}
