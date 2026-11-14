package com.crm.foundation.dental;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped default: {@code dental.public.enabled} is off, so nothing public answers and no link can be made. */
class VisitLinkDisabledIT extends VisitLinkITSupport {

    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> dentalPluginDirectory().toString());
    }

    @Test
    void public_disabled_hides_everything() {
        String token = adminToken();
        UUID id = idOf(book(token, futureDay()));

        ResponseEntity<String> created = post(linkPath(id), token, null);

        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(409);
        assertThat(json(created).path("code").asText()).isEqualTo("DENTAL_PUBLIC_DISABLED");
        assertThat(count("visit_link", "appointment_id", id)).isZero();

        // A link made while the route was on (here: a row of known token) is dead too
        String known = "known-token-" + tag();
        jdbc.update("""
            INSERT INTO dental.visit_link (appointment_id, token_hash, created_at, expires_at)
            VALUES (?, sha256(convert_to(?, 'UTF8')), now(), ?)
            """, id, known, java.sql.Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)));
        assertThat(resolveStatus(known)).isEqualTo(404);
        for (String path : new String[] {"/public/visit", "/public/visit/form", "/public/visit/id-scan"}) {
            ResponseEntity<String> response = exchange(HttpMethod.GET, DENTAL_API + path, null, null,
                "X-Visit-Token", known);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(404);
        }

        // Staff can still see and revoke what exists
        JsonNode status = data(get(linkPath(id), token));
        assertThat(status.path("exists").asBoolean()).isTrue();
        assertThat(exchange(HttpMethod.DELETE, linkPath(id), token, null).getStatusCode().value()).isEqualTo(204);
        assertThat(count("visit_link", "appointment_id", id)).isZero();
    }
}
