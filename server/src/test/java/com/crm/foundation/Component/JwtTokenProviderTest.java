package com.crm.foundation.Component;

import com.crm.foundation.Domain.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    /** Same secret as {@link JwtTokenProvider} (hardcoded until config exists). */
    private static SecretKey sameSigningKey() {
        return Keys.hmacShaKeyFor(
                "abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
    }

    @Test
    void generateToken_thenGetUserIdFromJWT_roundTripsSubject() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        String jwt = provider.generateToken(user);

        assertThat(provider.getUserIdFromJWT(jwt)).isEqualTo(id);
    }

    @Test
    void validateToken_returnsTrueForGeneratedToken() {
        User user = new User();
        user.setId(Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000002")));
        user.setUsername("bob");
        user.setEmail("bob@example.com");

        assertThat(provider.validateToken(provider.generateToken(user))).isTrue();
    }

    @Test
    void validateToken_returnsFalseForMalformedToken() {
        assertThat(provider.validateToken("not-a-jwt")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForBlank() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForTamperedToken() {
        User user = new User();
        user.setId(Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000003")));
        user.setUsername("carol");
        user.setEmail("carol@example.com");
        String jwt = provider.generateToken(user);
        String tampered = jwt.substring(0, jwt.length() - 1) + (jwt.endsWith("a") ? "b" : "a");

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_returnsFalseForExpiredToken() {
        Date past = new Date(System.currentTimeMillis() - 120_000);
        Date expiredAt = new Date(System.currentTimeMillis() - 60_000);
        String expired =
                Jwts.builder()
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(past)
                        .expiration(expiredAt)
                        .signWith(sameSigningKey())
                        .compact();

        assertThat(provider.validateToken(expired)).isFalse();
    }

    @Test
    void getUserIdFromJWT_throwsForExpiredToken() {
        Date past = new Date(System.currentTimeMillis() - 120_000);
        Date expiredAt = new Date(System.currentTimeMillis() - 60_000);
        String expired =
                Jwts.builder()
                        .subject(
                                Objects.requireNonNull(UUID.randomUUID())
                                        .toString())
                        .issuedAt(past)
                        .expiration(expiredAt)
                        .signWith(sameSigningKey())
                        .compact();

        assertThatThrownBy(() -> provider.getUserIdFromJWT(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void getUserIdFromJWT_throwsForInvalidToken() {
        assertThatThrownBy(() -> provider.getUserIdFromJWT("x.y.z"))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
}
