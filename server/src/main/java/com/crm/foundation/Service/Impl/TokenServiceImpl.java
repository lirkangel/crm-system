package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RefreshTokenRepository;
import com.crm.foundation.Service.TokenService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class TokenServiceImpl implements TokenService {

    private static final long REFRESH_TTL_SECONDS = 3600L;

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenServiceImpl(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    public RefreshToken createToken(User user) {
        Instant expiresAt = Instant.now().plusSeconds(REFRESH_TTL_SECONDS);
        RefreshToken token =
            refreshTokenRepository
                .findByUser(user)
                .map(
                    existing -> {
                        existing.setExpiresAt(expiresAt);
                        return existing;
                    })
                .orElseGet(() -> RefreshToken.issueFor(user, expiresAt));
        return refreshTokenRepository.saveAndFlush(Objects.requireNonNull(token, "token"));
    }

    @Override
    public RefreshToken updateToken(UUID jti) {
        RefreshToken existingToken = refreshTokenRepository.findByJti(jti);
        if (existingToken == null) {
            return null;
        }
        Instant expiresAt = Instant.now().plusSeconds(REFRESH_TTL_SECONDS);
        existingToken.setExpiresAt(expiresAt);
        refreshTokenRepository.saveAndFlush(existingToken);
        return existingToken;
    }

    @Override
    public Boolean issueToken(UUID jti) {
        RefreshToken existingToken = refreshTokenRepository.findByJti(jti);
        if (existingToken == null) {
            return false;
        }
        Instant expiresAt = Instant.now();
        existingToken.setExpiresAt(expiresAt);
        refreshTokenRepository.saveAndFlush(existingToken);
        return true;
    }
}
