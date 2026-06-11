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

    private static final String TEST_SECRET = "abcdefghijklmnopqrstuvwxyz123456";
    private static final long TEST_ACCESS_TTL_SECONDS = 3600L;

    private JwtTokenProvider provider;

    private static SecretKey sameSigningKey() {
        return Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(TEST_SECRET, TEST_ACCESS_TTL_SECONDS);
    }

    @Test
    void constructor_throwsWhenSecretTooShort() {
        assertThatThrownBy(() -> new JwtTokenProvider("short-secret", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foundation.jwt.secret must be at least 32 bytes");
    }

    @Test
    void constructor_throwsOnTtlMillisOverflow() {
        assertThatThrownBy(() -> new JwtTokenProvider(TEST_SECRET, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void issueAccessToken_thenGetUserIdFromJWT_roundTripsSubject() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        String jwt = provider.issueAccessToken(user, java.util.Set.of()).token();

        assertThat(provider.getUserIdFromJWT(jwt)).isEqualTo(id);
    }

    @Test
    void validateToken_returnsTrueForGeneratedToken() {
        User user = new User();
        user.setId(Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000002")));
        user.setUsername("bob");
        user.setEmail("bob@example.com");

        assertThat(provider.validateToken(provider.issueAccessToken(user, java.util.Set.of()).token())).isTrue();
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
    void validateToken_returnsFalseForNull() {
        assertThat(provider.validateToken(null)).isFalse();
    }

    @Test
    void validateToken_returnsFalseForWhitespace() {
        assertThat(provider.validateToken("   ")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForTamperedToken() {
        User user = new User();
        user.setId(Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000003")));
        user.setUsername("carol");
        user.setEmail("carol@example.com");
        String jwt = provider.issueAccessToken(user, java.util.Set.of()).token();
        // Tamper mid-payload: the final base64url char of the signature only carries
        // 2 significant bits, so flipping it can decode to the same bytes (flaky)
        int payloadMiddle = jwt.indexOf('.') + (jwt.indexOf('.', jwt.indexOf('.') + 1) - jwt.indexOf('.')) / 2;
        char original = jwt.charAt(payloadMiddle);
        String tampered = jwt.substring(0, payloadMiddle)
            + (original == 'a' ? 'b' : 'a')
            + jwt.substring(payloadMiddle + 1);

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

    @Test
    void getUserIdFromJWT_throwsWhenSubjectIsNotUuid() {
        String jwt =
                Jwts.builder()
                        .subject("not-a-uuid")
                        .issuedAt(new Date(System.currentTimeMillis() - 1_000))
                        .expiration(new Date(System.currentTimeMillis() + 60_000))
                        .signWith(sameSigningKey())
                        .compact();

        assertThatThrownBy(() -> provider.getUserIdFromJWT(jwt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
