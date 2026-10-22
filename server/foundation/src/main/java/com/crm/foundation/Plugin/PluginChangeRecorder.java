package com.crm.foundation.Plugin;

import com.crm.foundation.Service.ChangeEventService;

import java.time.Instant;
import java.util.UUID;

/** {@link ChangeRecorder} bound to one plugin id; delegates to the foundation change feed. */
public class PluginChangeRecorder implements ChangeRecorder {

    private final String pluginId;
    private final ChangeEventService changes;

    public PluginChangeRecorder(String pluginId, ChangeEventService changes) {
        this.pluginId = pluginId;
        this.changes = changes;
    }

    @Override
    public void record(String entityType, UUID entityId, long version, ChangeOp op) {
        changes.record(pluginId, entityType, entityId, version, op.name(), Instant.now());
    }
}
