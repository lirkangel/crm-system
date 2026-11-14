package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.PluginRegistry;
import com.crm.foundation.Service.PluginRegistryService;
import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.PluginPackageBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @PublicEndpoint}: login-free plugin routes under {@code /api/v1/plugins/{route}/public/}
 * — open to anyone, rate limited per client, never cached or indexed — and the registry rules
 * that keep every other plugin route guarded.
 */
@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
class PluginPublicRouteIT {

    private static final String PROXY_SECRET = "it-proxy-secret";
    private static final String PUB_PLUGIN_ID = "com.crm.pub";
    private static final String OUTSIDE_PLUGIN_ID = "com.crm.pubout";
    private static final String UNMARKED_PLUGIN_ID = "com.crm.pubmissing";
    private static final String BOTH_PLUGIN_ID = "com.crm.pubboth";
    private static final String CLASS_GUARDED_PLUGIN_ID = "com.crm.pubclass";
    private static final String BARE_PLUGIN_ID = "com.crm.pubbare";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PluginRegistryService registryService;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) throws IOException {
        Path pluginDir = Files.createTempDirectory("public-plugins-");
        buildPubPlugin(pluginDir.resolve("pub.zip"));
        buildPlugin(pluginDir.resolve("pubout.zip"), OUTSIDE_PLUGIN_ID, "pubout", "demo.pubout", """
            @GetMapping("/ping")
            @PublicEndpoint
            public String ping() {
                return "pong";
            }
            """);
        buildPlugin(pluginDir.resolve("pubmissing.zip"), UNMARKED_PLUGIN_ID, "pubmissing", "demo.pubmissing", """
            @GetMapping("/public/ping")
            @PreAuthorize("hasAuthority('x.y')")
            public String ping() {
                return "pong";
            }
            """);
        buildPlugin(pluginDir.resolve("pubboth.zip"), BOTH_PLUGIN_ID, "pubboth", "demo.pubboth", "", """
            @GetMapping("/public/ping")
            @PublicEndpoint
            @PreAuthorize("hasAuthority('x.y')")
            public String ping() {
                return "pong";
            }
            """);
        buildPlugin(pluginDir.resolve("pubclass.zip"), CLASS_GUARDED_PLUGIN_ID, "pubclass", "demo.pubclass",
            "@PreAuthorize(\"hasAuthority('x.y')\")", """
            @GetMapping("/public/ping")
            @PublicEndpoint
            public String ping() {
                return "pong";
            }
            """);
        buildPlugin(pluginDir.resolve("pubbare.zip"), BARE_PLUGIN_ID, "pubbare", "demo.pubbare", "", """
            @GetMapping("/public")
            public String ping() {
                return "pong";
            }
            """);
        registry.add("foundation.plugins.directory", pluginDir::toString);
        registry.add("crm.public.proxy-secret", () -> PROXY_SECRET);
        registry.add("crm.public.rate-limit-per-minute", () -> "60");
    }

    private static void buildPubPlugin(Path zip) throws IOException {
        buildPlugin(zip, PUB_PLUGIN_ID, "pub", "demo.pub", """
            @GetMapping("/public/hello")
            @PublicEndpoint
            public String hello() {
                return "hello";
            }

            @GetMapping("/guarded")
            @PreAuthorize("hasAuthority('pub.read')")
            public String guarded() {
                return "secret";
            }
            """);
    }

    private static void buildPlugin(Path zip, String id, String route, String pkg, String handlers) throws IOException {
        buildPlugin(zip, id, route, pkg, "", handlers);
    }

    private static void buildPlugin(Path zip, String id, String route, String pkg, String classAnnotation,
                                    String handlers) throws IOException {
        PluginPackageBuilder.plugin(id)
            .schema("plugin_" + route)
            .route(route)
            .entry(pkg + ".Plugin")
            .withClasspathOf(RestController.class, PreAuthorize.class, AliasFor.class)
            .source(pkg + ".Plugin", """
                package %s;
                public class Plugin implements com.crm.foundation.Plugin.PluginActivator {
                    public void onLoad() {
                    }
                }
                """.formatted(pkg))
            .source(pkg + ".Controller", """
                package %s;

                import com.crm.foundation.Plugin.PublicEndpoint;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                %s
                public class Controller {
                %s
                }
                """.formatted(pkg, classAnnotation, handlers))
            .buildTo(zip);
    }

    private ResponseEntity<String> get(String path, String clientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Guest-Proxy-Secret", PROXY_SECRET);
        headers.set("X-Client-IP", clientIp);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String freshIp() {
        return "198.51.100." + (UUID.randomUUID().getMostSignificantBits() & 0xff) + "-" + UUID.randomUUID();
    }

    @Test
    void public_endpoint_answers_without_token() {
        ResponseEntity<String> response = get("/api/v1/plugins/pub/public/hello", freshIp());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("hello");
    }

    @Test
    void guarded_endpoint_of_same_plugin_still_401() {
        ResponseEntity<String> response = get("/api/v1/plugins/pub/guarded", freshIp());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void public_annotation_outside_public_prefix_refuses_plugin() {
        PluginRegistry entry = registryService.findByPluginId(OUTSIDE_PLUGIN_ID).orElseThrow();

        assertThat(entry.getState()).isEqualTo(PluginState.LOAD_FAILED.name());
        assertThat(entry.getErrorMessage()).contains("@PublicEndpoint").contains("/public/");
        // its route is not served (and not public either: 401 without a token, 404 only for the public prefix)
        assertThat(get("/api/v1/plugins/pubout/public/ping", freshIp()).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/v1/plugins/pubout/ping", freshIp()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void public_prefix_without_annotation_refuses_plugin() {
        PluginRegistry entry = registryService.findByPluginId(UNMARKED_PLUGIN_ID).orElseThrow();

        assertThat(entry.getState()).isEqualTo(PluginState.LOAD_FAILED.name());
        assertThat(entry.getErrorMessage()).contains("@PublicEndpoint").contains("/public/");
        assertThat(get("/api/v1/plugins/pubmissing/public/ping", freshIp()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void rate_limit_answers_429_rate_limited() throws IOException {
        String client = freshIp();
        for (int i = 1; i <= 60; i++) {
            assertThat(get("/api/v1/plugins/pub/public/hello", client).getStatusCode().value())
                .as("request %d", i).isEqualTo(200);
        }

        ResponseEntity<String> limited = get("/api/v1/plugins/pub/public/hello", client);

        assertThat(limited.getStatusCode().value()).as(limited.getBody()).isEqualTo(429);
        assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = objectMapper.readTree(limited.getBody());
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("trace_id").asText()).isNotBlank();
        // another client is unaffected
        assertThat(get("/api/v1/plugins/pub/public/hello", freshIp()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void public_responses_carry_no_store_and_noindex() {
        ResponseEntity<String> ok = get("/api/v1/plugins/pub/public/hello", freshIp());
        ResponseEntity<String> notFound = get("/api/v1/plugins/pub/public/missing", freshIp());

        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        for (ResponseEntity<String> response : java.util.List.of(ok, notFound)) {
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex");
        }
    }

    @Test
    void encoded_public_segment_is_rejected_or_limited() throws IOException {
        for (String path : new String[] {"/api/v1/plugins/pub/%70ublic/hello", "/api/v1/plugins/p%75b/public/hello",
            "/api/v1/plugins/pub/%70ublic/guarded"}) {
            // a URI object, so the client does not encode the percent signs a second time
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Guest-Proxy-Secret", PROXY_SECRET);
            headers.set("X-Client-IP", freshIp());
            ResponseEntity<String> response = rest.exchange(
                java.net.URI.create(rest.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode().value()).as(path + " " + response.getBody()).isEqualTo(400);
            assertThat(response.getHeaders().getContentType()).as(path + " " + response.getBody()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(objectMapper.readTree(response.getBody()).path("code").asText()).isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    void public_and_pre_authorize_together_refuse_plugin() {
        assertRefused(BOTH_PLUGIN_ID, "@PreAuthorize");
    }

    @Test
    void class_level_pre_authorize_with_public_method_refuses_plugin() {
        assertRefused(CLASS_GUARDED_PLUGIN_ID, "@PreAuthorize");
    }

    @Test
    void unannotated_handler_at_bare_public_refuses_plugin() {
        assertRefused(BARE_PLUGIN_ID, "@PublicEndpoint");
    }

    private void assertRefused(String pluginId, String messagePart) {
        PluginRegistry entry = registryService.findByPluginId(pluginId).orElseThrow();

        assertThat(entry.getState()).isEqualTo(PluginState.LOAD_FAILED.name());
        assertThat(entry.getErrorMessage()).contains(messagePart);
    }
}
