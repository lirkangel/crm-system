package com.crm.foundation.Service.Impl;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RefreshTokenRepository;
import com.crm.foundation.Service.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class TokenServiceImpl implements TokenService {

    private final long refreshTtlSeconds;

    private final RefreshTokenRepository refreshTokenRepository;

    private final JwtTokenProvider jwtTokenProvider;

    public TokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                            @Value("${foundation.jwt.refresh-ttl-seconds}") long refreshTtlSeconds, JwtTokenProvider jwtTokenProvider) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public RefreshToken createToken(User user) {
        Instant expiresAt = Instant.now().plusSeconds(refreshTtlSeconds);
        RefreshToken token = RefreshToken.issueFor(user, expiresAt);
        return refreshTokenRepository.saveAndFlush(Objects.requireNonNull(token, "token"));
    }

    @Override
    public LogoutStatus revokeToken(UUID jti) {
        RefreshToken existingToken = refreshTokenRepository.findByJti(jti);
        if (existingToken == null) {
            return LogoutStatus.NOT_FOUND;
        }

        Instant now = Instant.now();

        if (existingToken.getRevokedAt() != null) {
            return LogoutStatus.ALREADY_REVOKED;
        }

        if (existingToken.getUsedAt() != null) {
            return LogoutStatus.ALREADY_USED;
        }

        if (existingToken.getExpiresAt().isBefore(now)) {
            return LogoutStatus.EXPIRED;
        }

        existingToken.setRevokedAt(now);
        refreshTokenRepository.saveAndFlush(existingToken);
        return LogoutStatus.REVOKED;
    }

    @Override
    public IssuedAccessToken createAccessToken(User user, Set<String> permissions) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(user.getId(), "user id");
        Objects.requireNonNull(permissions, "permissions");
        return jwtTokenProvider.issueAccessToken(user, permissions);
    }

    @Override
    public RefreshToken refreshToken(UUID jti) {
        RefreshToken existingToken =
            refreshTokenRepository.findByJti(jti);
        if (existingToken == null) {
            return null;
        }

        Instant now = Instant.now();

        if (existingToken.getExpiresAt().isBefore(now)) {
            return null;
        }

        if (existingToken.getRevokedAt() != null) {
            return null;
        }

        if (existingToken.getUsedAt() != null) {
            return null;
        }

        existingToken.setUsedAt(now);

        RefreshToken replacement =
            RefreshToken.issueFor(
                existingToken.getUser(),
                now.plusSeconds(refreshTtlSeconds));

        refreshTokenRepository.saveAndFlush(replacement);

        existingToken.setReplacedBy(replacement.getJti());
        refreshTokenRepository.saveAndFlush(existingToken);
        return replacement;
    }
}
