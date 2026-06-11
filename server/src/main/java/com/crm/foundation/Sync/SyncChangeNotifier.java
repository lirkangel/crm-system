package com.crm.foundation.Sync;

import com.crm.foundation.DTO.ChangeEventResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed change events to the WebSocket fan-out (T012a). Listens
 * AFTER_COMMIT so clients are never notified about rolled-back writes
 * (T011a's guarantee extends to push). Notification failures are logged, never
 * propagated — push is best-effort on top of the polling fallback.
 */
@Component
public class SyncChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(SyncChangeNotifier.class);

    private final SyncWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    public SyncChangeNotifier(SyncWebSocketHandler webSocketHandler, ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChangeRecorded(ChangeEventRecorded recorded) {
        try {
            webSocketHandler.broadcast(
                objectMapper.writeValueAsString(ChangeEventResponse.from(recorded.event())));
        } catch (Exception e) {
            log.warn("Change push failed (clients fall back to polling): {}", e.getMessage());
        }
    }
}
