package com.crm.foundation.Plugin;

import java.util.UUID;

/**
 * Lets a plugin append an event to the foundation's hash-chained audit log.
 * The foundation binds the plugin id and fills in the acting user and source IP.
 */
public interface AuditRecorder {

    void record(String entityType, UUID entityId, String op, String beforeJson, String afterJson);
}
