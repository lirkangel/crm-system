package com.crm.foundation.Auth;

import com.crm.foundation.Component.AdminBootstrapRunner;
import com.crm.foundation.Security.CorePermissions;
import com.crm.foundation.config.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T008c: a fresh server started with {@code BOOTSTRAP_ADMIN_*} set gets exactly one
 * usable admin, and re-running the bootstrap never adds or overwrites one.
 * <p>
 * The distinct properties give this class its own Spring context and therefore its
 * own Postgres container, so the database starts with no users.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "BOOTSTRAP_ADMIN_USERNAME=" + AdminBootstrapIT.USERNAME,
        "BOOTSTRAP_ADMIN_EMAIL=bootstrap@example.com",
        "BOOTSTRAP_ADMIN_PASSWORD=" + AdminBootstrapIT.PASSWORD,
    })
@Import(TestContainersConfig.class)
class AdminBootstrapIT {

    static final String USERNAME = "it_bootstrap";
    static final String PASSWORD = "first admin passphrase";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminBootstrapRunner runner;

    @Test
    void startup_creates_one_admin_who_can_log_in_with_every_core_permission() throws Exception {
        assertThat(adminUsernames()).containsExactly(USERNAME);

        ResponseEntity<String> login = rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", USERNAME, "password", PASSWORD), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String accessToken = data(login).path("accessToken").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> me =
            rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        List<String> permissions = new ArrayList<>();
        data(me).path("permissions").forEach(p -> permissions.add(p.asText()));
        assertThat(permissions).containsAll(CorePermissions.all());
    }

    @Test
    void startup_writes_bootstrap_audit_event_for_the_new_admin() {
        Integer events = jdbc.queryForObject(
            "SELECT count(*) FROM audit_event a JOIN users u ON u.id = a.entity_id "
                + "WHERE a.op = 'ADMIN_BOOTSTRAP' AND u.username = ?",
            Integer.class, USERNAME);
        assertThat(events).isEqualTo(1);
    }

    @Test
    void rerunning_bootstrap_neither_adds_nor_overwrites_an_admin() throws Exception {
        String hashBefore = passwordHash();

        runner.run(new DefaultApplicationArguments());

        assertThat(adminUsernames()).containsExactly(USERNAME);
        assertThat(passwordHash()).isEqualTo(hashBefore);
    }

    private List<String> adminUsernames() {
        return jdbc.queryForList(
            "SELECT u.username FROM users u "
                + "JOIN user_roles ur ON ur.user_id = u.id "
                + "JOIN roles r ON r.id = ur.role_id WHERE r.code = 'admin'",
            String.class);
    }

    private String passwordHash() {
        return jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE username = ?", String.class, USERNAME);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("data");
    }
}
