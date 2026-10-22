package com.crm.foundation.Sync;

import com.crm.foundation.Domain.ChangeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SyncChangeNotifierTest {

    private final List<String> broadcasts = new ArrayList<>();

    /** Subclass double — concrete classes can't be Mockito-mocked on this JDK. */
    private final SyncWebSocketHandler handler = new SyncWebSocketHandler() {
        @Override
        public void broadcast(String json) {
            broadcasts.add(json);
        }
    };

    private final SyncChangeNotifier notifier = new SyncChangeNotifier(handler, mapper());

    /** Mirrors Spring Boot's mapper: JavaTimeModule registered so Instant serializes. */
    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static ChangeEvent event() {
        ChangeEvent e = new ChangeEvent();
        e.setPluginId("core");
        e.setEntityType("User");
        e.setEntityId(UUID.fromString("00000000-0000-0000-0000-000000000042"));
        e.setVersion(1L);
        e.setOp("CREATE");
        e.setOccurredAt(Instant.parse("2026-06-11T10:00:00Z"));
        return e;
    }

    @Test
    void on_recorded_change_broadcasts_change_event_json() {
        notifier.onChangeRecorded(new ChangeEventRecorded(event()));

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0))
            .contains("\"entityType\":\"User\"")
            .contains("\"op\":\"CREATE\"")
            .contains("00000000-0000-0000-0000-000000000042");
    }

    @Test
    void broadcast_failure_does_not_propagate_into_commit_path() {
        SyncWebSocketHandler failing = new SyncWebSocketHandler() {
            @Override
            public void broadcast(String json) {
                throw new IllegalStateException("ws infrastructure down");
            }
        };

        new SyncChangeNotifier(failing, mapper())
            .onChangeRecorded(new ChangeEventRecorded(event()));
    }
}
