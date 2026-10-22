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
import jakarta.servlet.http.HttpServletResponse;
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
    private static final String OPEN_PLUGIN_ID = "com.crm.open";

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
        // Same plugin id, later in discovery order (an old version left behind): must be ignored
        buildDuplicateEchoPackage(pluginDir.resolve("zz-echo-duplicate.zip"));
        buildOpenPlugin(pluginDir.resolve("open.zip"));
        registry.add("foundation.plugins.directory", pluginDir::toString);
    }

    private static void buildEchoPlugin(Path zip) throws IOException {
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .schema(SCHEMA)
            .route("echo")
            .entry("demo.echo.EchoPlugin")
            .withClasspathOf(
                RestController.class, PreAuthorize.class, Transactional.class, JpaRepository.class,
                Repository.class, Service.class, AliasFor.class, HttpServletResponse.class)
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

                    public String getText() {
                        return text;
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

                    @Transactional(readOnly = true)
                    public String text(UUID id) {
                        return notes.findById(id).map(Note::getText)
                            .orElseThrow(() -> new PluginProblem(404, "ECHO_NOT_FOUND", "no such note"));
                    }
                }
                """)
            .source("demo.echo.EchoController", """
                package demo.echo;

                import com.crm.foundation.DTO.CommonResponse;
                import java.util.Map;
                import java.util.UUID;
                import org.springframework.http.HttpStatus;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.server.ResponseStatusException;

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

                    @GetMapping("/notes/{id}")
                    @PreAuthorize("hasAuthority('echo.ping')")
                    public CommonResponse<String> read(@PathVariable("id") UUID id) {
                        return CommonResponse.success(notes.text(id));
                    }

                    @GetMapping("/teapot")
                    @PreAuthorize("hasAuthority('echo.ping')")
                    public CommonResponse<String> teapot() {
                        throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT);
                    }

                    /** Goes through the servlet error-page (ERROR) dispatch, not an advice. */
                    @GetMapping("/send-error")
                    @PreAuthorize("hasAuthority('echo.ping')")
                    public void sendError(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
                        response.sendError(418, "secret-internal-detail");
                    }

                    @GetMapping("/boom")
                    @PreAuthorize("hasAuthority('echo.ping')")
                    public CommonResponse<String> boom() {
                        throw new RuntimeException("secret-internal-detail");
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

    private static void buildDuplicateEchoPackage(Path zip) throws IOException {
        PluginPackageBuilder.plugin(PLUGIN_ID)
            .version("0.0.9")
            .schema(SCHEMA)
            .route("echo-old")
            .entry("demo.echo.EchoPlugin")
            .source("demo.echo.EchoPlugin", """
                package demo.echo;
                public class EchoPlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """)
            // Differs from the real V1: if this package were migrated, Flyway validation would fail it
            .migration("V1__note.sql", "CREATE TABLE stale_note (id UUID PRIMARY KEY);")
            .buildTo(zip);
    }

    /** A handler method without {@code @PreAuthorize}: default-deny refuses the whole plugin. */
    private static void buildOpenPlugin(Path zip) throws IOException {
        PluginPackageBuilder.plugin(OPEN_PLUGIN_ID)
            .schema("plugin_open")
            .route("open")
            .entry("demo.open.OpenPlugin")
            .withClasspathOf(RestController.class, PreAuthorize.class, AliasFor.class)
            .source("demo.open.OpenPlugin", """
                package demo.open;
                public class OpenPlugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """)
            .source("demo.open.OpenController", """
                package demo.open;

                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class OpenController {
                    @GetMapping("/ping")
                    @PreAuthorize("hasAuthority('echo.ping')")
                    public String ping() {
                        return "pong";
                    }

                    @GetMapping("/anyone")
                    public String anyone() {
                        return "open to every authenticated user";
                    }
                }
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
    void unknown_plugin_route_is_404() throws IOException {
        ResponseEntity<String> response = get("/api/v1/plugins/nope/ping", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void unknown_path_without_token_is_401() {
        assertThat(get("/api/v1/plugins/nope/ping", null).getStatusCode().value()).isEqualTo(401);
        assertThat(post("/api/v1/plugins/nope/x", null, Map.of()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void malformed_json_is_400_problem() throws IOException {
        ResponseEntity<String> response = post("/api/v1/plugins/echo/notes", token("echo.write"), "{");

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void bad_uuid_path_variable_is_400_problem() throws IOException {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/notes/not-a-uuid", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void wrong_method_is_405_problem() throws IOException {
        ResponseEntity<String> response = rest.exchange("/api/v1/plugins/echo/ping", HttpMethod.DELETE,
            new HttpEntity<>(headers(token("echo.ping"))), String.class);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(405);
        assertThat(json(response).path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void send_error_keeps_its_status_for_authenticated_caller() {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/send-error", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(418);
        assertThat(response.getBody()).doesNotContain("secret-internal-detail");
    }

    @Test
    void response_status_exception_keeps_its_status() throws IOException {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/teapot", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(418);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("code").asText()).isEqualTo("I_AM_A_TEAPOT");
    }

    @Test
    void unexpected_plugin_exception_is_generic_500_problem() throws IOException {
        ResponseEntity<String> response = get("/api/v1/plugins/echo/boom", token("echo.ping"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).path("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(json(response).path("trace_id").asText()).isNotBlank();
        assertThat(response.getBody()).doesNotContain("secret-internal-detail");
    }

    @Test
    void duplicate_plugin_id_package_is_ignored() {
        PluginRegistry entry = registryService.findByPluginId(PLUGIN_ID).orElseThrow();

        assertThat(entry.getState()).isEqualTo(PluginState.ACTIVE.name());
        assertThat(entry.getVersion()).isEqualTo("1.0.0");
        assertThat(get("/api/v1/plugins/echo/ping", token("echo.ping")).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/v1/plugins/echo-old/ping", token("echo.ping")).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'stale_note'",
            Integer.class, SCHEMA)).isZero();
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
    void plugin_with_a_handler_lacking_method_security_is_load_failed() {
        PluginRegistry entry = registryService.findByPluginId(OPEN_PLUGIN_ID).orElseThrow();

        assertThat(entry.getState()).isEqualTo(PluginState.LOAD_FAILED.name());
        assertThat(entry.getErrorMessage()).contains("@PreAuthorize").contains("demo.open.OpenController.anyone");
        assertThat(get("/api/v1/plugins/open/anyone", token("something.else")).getStatusCode().value())
            .isEqualTo(404);
        assertThat(get("/api/v1/plugins/open/ping", token("echo.ping")).getStatusCode().value()).isEqualTo(404);
        // the other plugin is unaffected
        assertThat(get("/api/v1/plugins/echo/ping", token("echo.ping")).getStatusCode().value()).isEqualTo(200);
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
