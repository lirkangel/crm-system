package com.crm.foundation.Sync;

import com.crm.foundation.Component.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Authenticates the {@code /ws/sync} handshake: browsers can't set an
 * {@code Authorization} header on WebSocket upgrades, so the access JWT is
 * passed as a {@code token} query parameter and validated before upgrade.
 */
@Component
public class SyncHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SyncHandshakeInterceptor.class);

    private final JwtTokenProvider tokenProvider;

    public SyncHandshakeInterceptor(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
            .build()
            .getQueryParams()
            .getFirst("token");
        if (token == null || !tokenProvider.validateToken(token)) {
            log.debug("Rejected sync WS handshake: missing or invalid token");
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // nothing to do
    }
}
