package com.crm.foundation.DTO;

import com.crm.foundation.Domain.RefreshToken;

import java.time.Instant;

public record AuthResponse(
    String accessToken,
    Instant accessTokenExpiresAt,
    String refreshToken,
    Instant refreshTokenExpiresAt,
    String tokenType
) {
    public static final String TOKEN_TYPE = "Bearer";

    public static AuthResponse from(String accessToken, Instant accessTokenExpiresAt, RefreshToken refreshToken) {
        return new AuthResponse(
            accessToken,
            accessTokenExpiresAt,
            refreshToken.getJti().toString(),
            refreshToken.getExpiresAt(),
            TOKEN_TYPE);
    }
}
