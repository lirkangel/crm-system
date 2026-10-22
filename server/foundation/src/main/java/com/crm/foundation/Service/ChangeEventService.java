package com.crm.foundation.Service;

import com.crm.foundation.Domain.ChangeEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChangeEventService {

    void record(String pluginId, String entityType, UUID entityId, long version, String op, Instant occurredAt);

    /** Change events strictly after {@code since}, oldest first (delta feed, T012). */
    List<ChangeEvent> changesSince(Instant since);
}
