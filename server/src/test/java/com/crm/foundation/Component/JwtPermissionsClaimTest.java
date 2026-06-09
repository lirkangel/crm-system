package com.crm.foundation.Component;

import com.crm.foundation.Domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPermissionsClaimTest {

    private static final String SECRET = "abcdefghijklmnopqrstuvwxyz123456";
    private JwtTokenProvider provider;
    private User user;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3600L);
        user = new User();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        user.setUsername("alice");
        user.setEmail("alice@example.com");
    }

    @Test
    void issueAccessToken_permsClaim_roundTrips() {
        Set<String> perms = Set.of("core.users.read", "core.roles.read");

        String jwt = provider.issueAccessToken(user, perms).token();
        Set<String> extracted = provider.getPermissionsFromJWT(jwt);

        assertThat(extracted).containsExactlyInAnyOrderElementsOf(perms);
    }

    @Test
    void issueAccessToken_emptyPerms_extractsEmptySet() {
        String jwt = provider.issueAccessToken(user, Set.of()).token();

        assertThat(provider.getPermissionsFromJWT(jwt)).isEmpty();
    }

    @Test
    void getPermissionsFromJWT_tokenWithoutPermsClaim_returnsEmptySet() {
        // Token issued before T005a (no perms claim) — must not throw
        String legacyJwt = io.jsonwebtoken.Jwts.builder()
            .subject(user.getId().toString())
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .compact();

        assertThat(provider.getPermissionsFromJWT(legacyJwt)).isEmpty();
    }
}
