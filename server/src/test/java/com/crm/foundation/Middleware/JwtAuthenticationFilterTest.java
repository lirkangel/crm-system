package com.crm.foundation.Middleware;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.UserService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private JwtAuthenticationFilter filter;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtTokenProvider = new JwtTokenProvider("abcdefghijklmnopqrstuvwxyz123456", 3600L);
        filter =
                new JwtAuthenticationFilter(
                        jwtTokenProvider,
                        new InMemoryUserService(Map.of()),
                        new NoopRoleService());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeader_doesNotAuthenticate() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authorizationHeaderWithoutBearerPrefix_doesNotAuthenticate() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Token abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void bearerWithoutSpace_doesNotAuthenticate() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearerabc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void bearerEmptyToken_doesNotAuthenticate() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_userNotFound_doesNotAuthenticate() throws ServletException, IOException {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        User user = new User();
        user.setId(userId);
        user.setUsername("u");
        user.setEmail("u@example.com");

        String jwt = jwtTokenProvider.generateToken(user);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_userFound_setsAuthentication() throws ServletException, IOException {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        User user = new User();
        user.setId(userId);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        String jwt = jwtTokenProvider.generateToken(user);

        filter =
                new JwtAuthenticationFilter(
                        jwtTokenProvider,
                        new InMemoryUserService(Map.of(userId, user)),
                        new NoopRoleService());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isSameAs(user);
        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).isEmpty();
        assertThat(auth.getDetails()).isNotNull();
    }

    @Test
    void userIdParsingThrows_filterStillContinuesWithoutAuthentication() throws ServletException, IOException {
        String jwt =
                io.jsonwebtoken.Jwts.builder()
                        .subject("not-a-uuid")
                        .issuedAt(new java.util.Date(System.currentTimeMillis() - 1_000))
                        .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                        .signWith(
                                io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                                        "abcdefghijklmnopqrstuvwxyz123456"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .compact();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private record InMemoryUserService(Map<UUID, User> byId) implements UserService {
        @Override
        public Optional<User> findById(@NonNull UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public java.util.List<User> findAll() {
            return java.util.List.copyOf(byId.values());
        }

        @Override
        public Optional<User> findByUsername(@NonNull String username) {
            return Optional.empty();
        }

        @Override
        public Boolean checkUserByUsernamePassword(LoginRequest loginRequest) {
            return false;
        }

        @Override
        public User register(LoginRequest loginRequest) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopRoleService implements RoleService {
        @Override
        public Optional<com.crm.foundation.Domain.Role> findById(@NonNull UUID id) {
            return Optional.empty();
        }

        @Override
        public java.util.List<com.crm.foundation.Domain.Role> findByName(String name) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.crm.foundation.Domain.Role> findAll() {
            return java.util.List.of();
        }
    }
}

