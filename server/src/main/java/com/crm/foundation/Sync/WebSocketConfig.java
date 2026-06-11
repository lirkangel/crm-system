package com.crm.foundation.Sync;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the sync change-push endpoint (T012a). */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SyncWebSocketHandler syncWebSocketHandler;
    private final SyncHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(
            SyncWebSocketHandler syncWebSocketHandler,
            SyncHandshakeInterceptor handshakeInterceptor) {
        this.syncWebSocketHandler = syncWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(syncWebSocketHandler, "/ws/sync")
            .addInterceptors(handshakeInterceptor)
            .setAllowedOrigins("*");
    }
}
