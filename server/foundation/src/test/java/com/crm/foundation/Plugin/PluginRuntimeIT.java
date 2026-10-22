package com.crm.foundation.Plugin;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.PluginRegistryService;
import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.Repository;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Late plugin phase (design 6.1–6.3): a READY plugin gets its own Spring child
 * context — controllers, {@code @Transactional} services and Spring Data
 * repositories against Foundation's {@code EntityManagerFactory} — and its
 * {@code @RestController}s are served under {@code /api/v1/plugins/{route}},
 * guarded by method security.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
class PluginRuntimeIT {

    private static final String PLUGIN_ID = "com.crm.echo";
    private static final String SCHEMA = "plugin_echo";
    private static final String USERNAME = "it_echo_user";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtTokenProvider tokens;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PluginRegistryService registryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    private User user;

    /** Builds the echo plugin package before the context (and the plugin host) starts. */
    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("runtime-plugins-");
        buildEchoPlugin(pluginDir.resolve("echo.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private static void buildEchoPlugin(Path zip) throws IOException {
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .schema(SCHEMA)
            .route("echo")
            .entry("demo.echo.EchoPlugin")
            .withClasspathOf(
                RestController.class, PreAuthorize.class, Transactional.class, JpaRepository.class,
                Repository.class, Service.class, AliasFor.class)
            .source("demo.echo.EchoPlugin", """
                package demo.echo;
                public class EchoPlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """)
            .source("demo.echo.Note", """
                package demo.echo;

                import jakarta.persistence.*;
                import java.util.UUID;

                @Entity
                @Table(schema = "plugin_echo", name = "note")
                public class Note {
                    @Id
                    private UUID id;
                    @Version
                    private Long version;
                    private String text;

                    protected Note() {
                    }

                    public Note(UUID id, String text) {
                        this.id = id;
                        this.text = text;
                    }

                    public UUID getId() {
                        return id;
                    }

                    public Long getVersion() {
                        return version;
                    }
                }
                """)
            .source("demo.echo.NoteRepository", """
                package demo.echo;

                import java.util.UUID;
                import org.springframework.data.jpa.repository.JpaRepository;

                public interface NoteRepository extends JpaRepository<Note, UUID> {
                }
                """)
            .source("demo.echo.NoteService", """
                package demo.echo;

                import com.crm.foundation.Plugin.ChangeOp;
                import com.crm.foundation.Plugin.ChangeRecorder;
                import com.crm.foundation.Plugin.PluginProblem;
                import java.util.UUID;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @Transactional
                public class NoteService {
                    private final NoteRepository notes;
                    private final ChangeRecorder changes;

                    public NoteService(NoteRepository notes, ChangeRecorder changes) {
                        this.notes = notes;
                        this.changes = changes;
                    }

                    public UUID create(String text) {
                        Note note = notes.saveAndFlush(new Note(UUID.randomUUID(), text));
                        changes.record("Note", note.getId(), note.getVersion(), ChangeOp.CREATE);
                        if (text.startsWith("fail")) {
                            throw new PluginProblem(409, "ECHO_REJECTED", "rejected after write");
                        }
                        return note.getId();
                    }
                }
                """)
            .source("demo.echo.EchoController", """
                package demo.echo;

                import com.crm.foundation.DTO.CommonResponse;
                import java.util.Map;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class EchoController {
                    private final NoteService notes;

                    public EchoController(NoteService notes) {
                        this.notes = notes;
                    }

                    @GetMapping("/ping")
                    @PreAuthorize("hasAuthority('echo.ping')")
                    public CommonResponse<String> ping() {
                        return CommonResponse.success("pong");
                    }

                    @PostMapping("/notes")
                    @PreAuthorize("hasAuthority('echo.write')")
                    public CommonResponse<String> create(@RequestBody Map<String, String> body) {
                        return CommonResponse.success(notes.create(body.get("text")).toString());
                    }
                }
                """)
            .migration("V1__note.sql", """
                CREATE TABLE note (
                    id      UUID PRIMARY KEY,
                    version BIGINT NOT NULL,
                    text    VARCHAR(255) NOT NULL
                );
                """)
            .buildTo(zip);
    }

    @BeforeEach
    void seedUser() {
        user = userRepository.findByUsername(USERNAME).orElseGet(() -> {
            User created = new User();
            created.setUsername(USERNAME);
            created.setEmail(USERNAME + "@example.com");
            created.setPassword(BCrypt.hashpw("irrelevant password", BCrypt.gensalt()));
            return userRepository.save(created);
        });
    }

    private String token(String... permissions) {
        return tokens.issueAccessToken(user, Set.of(permissions)).token();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(bearer)), String.class);
    }

    private ResponseEntity<String> post(String path, String bearer, Object body) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private JsonNode json(ResponseEntity<String> response) throws IOException {
        return objectMapper.readTree(response.getBody());
    }

    @Test
    void plugin_route_served_under_prefix() throws IOException {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/ping", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(json(response).path("data").asText()).isEqualTo("pong");
    }

    @Test
    void plugin_route_enforces_permission() throws IOException {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/ping", token("something.else"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(403);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void plugin_route_requires_authentication() {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/ping", null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void unknown_plugin_route_is_404() {
        ResponseEntity<String> response = get("/api/v1/plugins/nope/ping", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
    }

    @Test
    void plugin_service_write_records_change_event_atomically() throws IOException {
        String bearer = token("echo.write");

        ResponseEntity<String> created = post("/api/v1/plugins/echo/notes", bearer, Map.of("text", "hello"));

        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(200);
        UUID noteId = UUID.fromString(json(created).path("data").asText());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM plugin_echo.note WHERE id = ?", Integer.class, noteId)).isOne();
        Map<String, Object> event = jdbc.queryForMap(
            "SELECT plugin_id, entity_type, op FROM change_event WHERE entity_id = ?", noteId);
        assertThat(event).containsEntry("plugin_id", PLUGIN_ID)
            .containsEntry("entity_type", "Note")
            .containsEntry("op", "CREATE");

        // The plugin fails after writing: note and change_event roll back together
        String failText = "fail-" + UUID.randomUUID();
        ResponseEntity<String> rejected = post("/api/v1/plugins/echo/notes", bearer, Map.of("text", failText));

        assertThat(rejected.getStatusCode().value()).as(rejected.getBody()).isEqualTo(409);
        assertThat(json(rejected).path("code").asText()).isEqualTo("ECHO_REJECTED");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM plugin_echo.note WHERE text = ?", Integer.class, failText)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM change_event e WHERE e.plugin_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM plugin_echo.note n WHERE n.id = e.entity_id)",
            Integer.class, PLUGIN_ID)).isZero();
    }

    @Test
    void registry_shows_plugin_active() {
        PluginRegistry entry = registryService.findByPluginId(PLUGIN_ID).orElseThrow();

        assertThat(entry.getState()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(entry.getErrorMessage()).isNull();
    }

    @Test
    void child_configuration_stays_out_of_foundation_context() {
        assertThat(applicationContext.getBeanNamesForType(PluginChildConfiguration.class)).isEmpty();
    }
}
