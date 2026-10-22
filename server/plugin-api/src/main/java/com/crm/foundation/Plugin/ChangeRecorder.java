package com.crm.foundation.Plugin;

import java.util.UUID;

/**
 * Lets a plugin publish an entity change to the foundation's sync change feed.
 * The foundation binds the plugin id, so a plugin can only record its own events.
 */
public interface ChangeRecorder {

    void record(String entityType, UUID entityId, long version, ChangeOp op);
}
