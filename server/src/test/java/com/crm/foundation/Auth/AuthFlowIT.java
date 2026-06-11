package com.crm.foundation.Auth;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.config.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T017: core auth/security paths proven end-to-end over HTTP against real
 * Postgres — login, bearer-protected access, refresh rotation (single use),
 * 403 permission denial, and the audit trail of it all. Complements
 * {@code AuditServiceIT} / {@code ChangeEventSameTransactionIT}.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
class AuthFlowIT {

    private static final String ADMIN = "it_admin";
    private static final String LOWLY = "it_lowly";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedUsers() {
        ensureUser(ADMIN, true);
        ensureUser(LOWLY, false);
    }

    private void ensureUser(String username, boolean admin) {
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        if (admin) {
            user.getRoles().add(roleRepository.findByCode("admin").orElseThrow());
        }
        userRepository.save(user);
    }

    private JsonNode login(String username) {
        ResponseEntity<String> response = rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", username, "password", PASSWORD), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return data(response);
    }

    private JsonNode data(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable response: " + response.getBody(), e);
        }
    }

    private ResponseEntity<String> getWithBearer(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void login_returns_usable_bearer_token_for_protected_endpoint() {
        JsonNode tokens = login(ADMIN);
        assertThat(tokens.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.path("accessToken").asText()).isNotBlank();
        assertThat(tokens.path("refreshToken").asText()).isNotBlank();

        ResponseEntity<String> me = getWithBearer("/api/v1/auth/me", tokens.path("accessToken").asText());
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(data(me).path("username").asText()).isEqualTo(ADMIN);
    }

    @Test
    void protected_endpoint_rejects_missing_token_with_401() {
        ResponseEntity<String> response = getWithBearer("/api/v1/auth/me", null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void user_without_permission_gets_403_problem_not_401() {
        JsonNode tokens = login(LOWLY);
        String adminId = userRepository.findByUsername(ADMIN).orElseThrow().getId().toString();

        ResponseEntity<String> response =
            getWithBearer("/api/v1/users/" + adminId, tokens.path("accessToken").asText());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void user_with_permission_reads_protected_resource() {
        JsonNode tokens = login(ADMIN);
        String adminId = userRepository.findByUsername(ADMIN).orElseThrow().getId().toString();

        ResponseEntity<String> response =
            getWithBearer("/api/v1/users/" + adminId, tokens.path("accessToken").asText());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void refresh_token_rotates_and_old_token_is_single_use() {
        JsonNode tokens = login(ADMIN);
        String originalRefresh = tokens.path("refreshToken").asText();

        ResponseEntity<String> rotated = rest.postForEntity(
            "/api/v1/auth/refresh", Map.of("refreshToken", originalRefresh), String.class);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String newRefresh = data(rotated).path("refreshToken").asText();
        assertThat(newRefresh).isNotBlank().isNotEqualTo(originalRefresh);

        ResponseEntity<String> replay = rest.postForEntity(
            "/api/v1/auth/refresh", Map.of("refreshToken", originalRefresh), String.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void login_writes_audit_event() {
        Integer before = auditLoginCount();
        login(ADMIN);
        assertThat(auditLoginCount()).isGreaterThan(before);
    }

    private Integer auditLoginCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE op = 'AUTH_LOGIN_SUCCESS'", Integer.class);
    }
}
