package com.crm.foundation.hotel;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.config.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class of the hotel ITs: boots the full application on a random port
 * against Testcontainers Postgres with the real packaged hotel ZIP (copied to
 * {@code target/test-plugins} by the build) as the only installed plugin.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
public abstract class HotelITSupport {

    protected static final String HOTEL_PLUGIN_ID = "com.crm.hotel";
    protected static final String HOTEL_SCHEMA = "hotel";
    protected static final String HOTEL_API = "/api/v1/plugins/hotel";

    private static final String ADMIN_USERNAME = "hotel_it_admin";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    /** Points the plugin host at the directory holding only the packaged hotel ZIP. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> hotelPluginDirectory().toString());
    }

    /**
     * Absolute {@code <module>/target/test-plugins}, derived from this class's
     * own location ({@code target/test-classes}): under {@code forkCount=0} the
     * JVM's working directory is the reactor root, so a relative path is wrong.
     */
    static Path hotelPluginDirectory() {
        try {
            Path testClasses = Path.of(HotelITSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = testClasses.getParent().resolve("test-plugins").toAbsolutePath();
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("only the packaged hotel plugin belongs in " + dir)
                    .singleElement().asString().isEqualTo("hotel-plugin.zip");
            }
            return dir;
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("hotel plugin directory target/test-plugins is not usable", e);
        }
    }

    /** Access token of an {@code admin} user (created on first use), obtained by logging in over HTTP. */
    protected String adminToken() {
        if (userRepository.findByUsername(ADMIN_USERNAME).isEmpty()) {
            User admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setEmail(ADMIN_USERNAME + "@example.com");
            // Same hashing as UserServiceImpl (static BCrypt; there is no PasswordEncoder bean)
            admin.setPassword(BCrypt.hashpw(ADMIN_PASSWORD, BCrypt.gensalt()));
            admin.getRoles().add(roleRepository.findByCode("admin").orElseThrow());
            userRepository.save(admin);
        }
        ResponseEntity<String> login = rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), String.class);
        assertThat(login.getStatusCode().value()).as(login.getBody()).isEqualTo(200);
        return data(login).path("accessToken").asText();
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
            headers.set(extraHeaders[i], extraHeaders[i + 1]);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** Whole response body parsed as JSON. */
    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (IOException e) {
            throw new IllegalStateException("Unparseable response: " + response.getBody(), e);
        }
    }

    /** The {@code data} member of the {@code CommonResponse} envelope. */
    protected JsonNode data(ResponseEntity<String> response) {
        return json(response).path("data");
    }
}
