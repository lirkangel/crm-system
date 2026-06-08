package com.crm.foundation.Service;

import com.crm.foundation.Audit.AuditPayload;

public interface AuditService {

    /**
     * Appends an event to the hash chain. Writes are serialized internally
     * (see {@code AuditWriter}) so {@code prevHash} always links to the
     * immediately preceding event, even under concurrent calls.
     */
    void record(AuditPayload payload);
}
