package com.crm.foundation.DTO;

import com.crm.foundation.Domain.RefreshToken;

import java.time.Instant;

public record AuthResponse(
    String accessToken,
    Instant accessTokenExpiredAt,
    String refreshToken,
    Instant refreshTokenExpireAt
) {
    public static final String TOKEN_TYPE = "Bearer";

    public static AuthResponse from(String accessToken, Instant accessTokenExpiredAt, RefreshToken refreshToken) {
        return new AuthResponse(
            accessToken,
            accessTokenExpiredAt,
            refreshToken.getJti().toString(),
            refreshToken.getExpiresAt());
    }
}
