package com.crm.foundation.dental;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.config.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base of the dental ITs: boots the full application on Testcontainers Postgres with the real packaged hotel and
 * dental ZIPs (copied to {@code target/test-plugins-dental} by the build) as the only installed plugins.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
public abstract class DentalITSupport {

    protected static final String DENTAL_PLUGIN_ID = "com.crm.dental";
    protected static final String DENTAL_SCHEMA = "dental";
    protected static final String HOTEL_PLUGIN_ID = "com.crm.hotel";
    protected static final String DENTAL_API = "/api/v1/plugins/dental";

    private static final String ADMIN_USERNAME = "dental_it_admin";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JwtTokenProvider tokens;

    /** The JDK client speaks PATCH, which the default HttpURLConnection-based one does not. */
    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /** Access token of an {@code admin} user (created on first use), obtained by logging in over HTTP. */
    protected String adminToken() {
        if (userRepository.findByUsername(ADMIN_USERNAME).isEmpty()) {
            User admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setEmail(ADMIN_USERNAME + "@example.com");
            admin.setPassword(BCrypt.hashpw(ADMIN_PASSWORD, BCrypt.gensalt()));
            admin.getRoles().add(roleRepository.findByCode("admin").orElseThrow());
            userRepository.save(admin);
        }
        ResponseEntity<String> login = rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), String.class);
        assertThat(login.getStatusCode().value()).as(login.getBody()).isEqualTo(200);
        return data(login).path("accessToken").asText();
    }

    /** Access token of a persisted non-admin user holding only {@code permissions}. */
    protected String tokenWith(String username, String... permissions) {
        User user = userRepository.findByUsername(username).orElseGet(() -> {
            User created = new User();
            created.setUsername(username);
            created.setEmail(username + "@example.com");
            created.setPassword(BCrypt.hashpw("irrelevant password", BCrypt.gensalt()));
            return userRepository.save(created);
        });
        return tokens.issueAccessToken(user, Set.of(permissions)).token();
    }

    protected ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected ResponseEntity<String> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ResponseEntity<String> put(String path, String token, Object body, String ifMatch) {
        return exchange(HttpMethod.PUT, path, token, body, "If-Match", ifMatch);
    }

    protected ResponseEntity<String> patch(String path, String token, Object body, String ifMatch) {
        return exchange(HttpMethod.PATCH, path, token, body, "If-Match", ifMatch);
    }

    protected ResponseEntity<String> exchange(
            HttpMethod method, String path, String token, Object body, String... extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            if (extraHeaders[i + 1] != null) {
                headers.set(extraHeaders[i], extraHeaders[i + 1]);
            }
        }
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        if (path.contains("%")) {
            // Already percent-encoded by the caller: send it as it is instead of encoding the % again
            return rest.exchange(java.net.URI.create(rest.getRootUri() + path), method, entity, String.class);
        }
        return rest.exchange(path, method, entity, String.class);
    }

    /** Whole response body parsed as JSON. */
    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unparseable response: " + response.getBody(), e);
        }
    }

    /** The {@code data} member of the {@code CommonResponse} envelope. */
    protected JsonNode data(ResponseEntity<String> response) {
        return json(response).path("data");
    }

    /** Absolute {@code <module>/target/test-plugins-dental}, derived from this class's own location. */
    protected static Path dentalPluginDirectory() {
        try {
            Path testClasses = Path.of(DentalITSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = testClasses.getParent().resolve("test-plugins-dental").toAbsolutePath();
            try (Stream<Path> files = Files.list(dir)) {
                List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
                assertThat(names).as("only hotel and dental belong in " + dir)
                    .containsExactly("dental-plugin.zip", "hotel-plugin.zip");
            }
            return dir;
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("dental plugin directory target/test-plugins-dental is not usable", e);
        }
    }
}
