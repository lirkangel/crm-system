package com.crm.foundation.Service;

import java.time.Instant;
import java.util.UUID;

public interface ChangeEventService {
    void record(String pluginId, String entityType, UUID entityId, long version, String op, Instant occurredAt);
}
