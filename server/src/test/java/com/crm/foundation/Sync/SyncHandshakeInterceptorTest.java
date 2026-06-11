package com.crm.foundation.Sync;

import com.crm.foundation.Component.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncHandshakeInterceptorTest {

    // Real provider — same test configuration as JwtTokenProviderTest
    private final JwtTokenProvider tokenProvider =
        new JwtTokenProvider("abcdefghijklmnopqrstuvwxyz123456", 900L);

    private final SyncHandshakeInterceptor interceptor = new SyncHandshakeInterceptor(tokenProvider);

    private boolean handshake(String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        return interceptor.beforeHandshake(
            request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>());
    }

    @Test
    void accepts_handshake_with_valid_access_token() {
        com.crm.foundation.Domain.User user = new com.crm.foundation.Domain.User();
        user.setId(UUID.randomUUID());
        String token = tokenProvider.issueAccessToken(user, java.util.Set.of()).token();

        assertThat(handshake("ws://localhost/ws/sync?token=" + token)).isTrue();
    }

    @Test
    void rejects_handshake_without_token() {
        assertThat(handshake("ws://localhost/ws/sync")).isFalse();
    }

    @Test
    void rejects_handshake_with_invalid_token() {
        assertThat(handshake("ws://localhost/ws/sync?token=not.a.jwt")).isFalse();
    }
}
