package com.crm.foundation.Sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncWebSocketHandlerTest {

    @Mock
    private WebSocketSession session;

    @Mock
    private WebSocketSession otherSession;

    private final SyncWebSocketHandler handler = new SyncWebSocketHandler();

    @Test
    void broadcast_sends_message_to_connected_sessions() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(otherSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        handler.afterConnectionEstablished(otherSession);

        handler.broadcast("{\"op\":\"CREATE\"}");

        verify(session).sendMessage(new TextMessage("{\"op\":\"CREATE\"}"));
        verify(otherSession).sendMessage(new TextMessage("{\"op\":\"CREATE\"}"));
    }

    @Test
    void broadcast_skips_closed_sessions() throws Exception {
        when(session.isOpen()).thenReturn(false);
        handler.afterConnectionEstablished(session);

        handler.broadcast("payload");

        verify(session, never()).sendMessage(new TextMessage("payload"));
    }

    @Test
    void disconnected_session_no_longer_receives_broadcasts() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        handler.broadcast("payload");

        verify(session, never()).sendMessage(new TextMessage("payload"));
    }

    @Test
    void one_failing_session_does_not_block_others() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(otherSession.isOpen()).thenReturn(true);
        org.mockito.Mockito.doThrow(new java.io.IOException("pipe broken"))
            .when(session).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        handler.afterConnectionEstablished(session);
        handler.afterConnectionEstablished(otherSession);

        handler.broadcast("payload");

        verify(otherSession).sendMessage(new TextMessage("payload"));
    }
}
