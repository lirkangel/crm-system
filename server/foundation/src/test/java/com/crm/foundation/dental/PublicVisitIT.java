package com.crm.foundation.dental;

import com.crm.foundation.hotel.CccdQrImages;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** The patient's public pre-visit API, with the public route switched on. */
class PublicVisitIT extends VisitLinkITSupport {

    private static final String TEST_KEY = "nERRVND9i0MldQ9zNhuM13SCQNs6+GfEj5DYcyrJiRw=";
    private static final String PUBLIC = DENTAL_API + "/public/visit";
    private static final String HEADER = "X-Visit-Token";
    private static final String BASE = "https://nhakhoa.mathisterben.org/#d/";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> dentalPluginDirectory().toString());
        registry.add("dental.public.enabled", () -> "true");
        registry.add("dental.field-key", () -> TEST_KEY);
        registry.add("crm.public.rate-limit-per-minute", () -> "100000");
    }

    /** An appointment for a patient born on {@code dob}, with a link; staff token, id and patient token. */
    private record Visit(String staff, UUID id, String token) {
    }

    private Visit visit(LocalDate dob) {
        String staff = adminToken();
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("fullName", "Tran Thi Binh " + tag());
        patient.put("phone", "09" + (10_000_000 + ThreadLocalRandom.current().nextLong(89_000_000)));
        patient.put("dateOfBirth", dob.toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newPatient", patient);
        body.put("startsAt", futureDay().atTime(10, 0).atOffset(CLINIC));
        ResponseEntity<String> booked = post(APPOINTMENTS, staff, body);
        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        UUID id = idOf(data(booked));
        ResponseEntity<String> link = post(linkPath(id), staff, null);
        assertThat(link.getStatusCode().value()).as(link.getBody()).isEqualTo(201);
        return new Visit(staff, id, data(link).path("url").asText().substring(BASE.length()));
    }

    private Visit adult() {
        return visit(LocalDate.of(1990, 5, 17));
    }

    private ResponseEntity<String> summary(String token) {
        return exchange(HttpMethod.GET, PUBLIC, null, null, HEADER, token);
    }

    private ResponseEntity<String> submit(String token, Object body) {
        return exchange(HttpMethod.POST, PUBLIC + "/form", null, body, HEADER, token);
    }

    private ResponseEntity<String> previous(String token, String dob) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateOfBirth", dob);
        return exchange(HttpMethod.POST, PUBLIC + "/previous", null, body, HEADER, token);
    }

    private static Map<String, Object> identity() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("idType", "CCCD");
        id.put("idNumber", "079090123456");
        id.put("fullName", "TRAN THI BINH");
        id.put("dateOfBirth", "1990-05-17");
        id.put("nationality", "VN");
        id.put("address", "12 Le Loi, Phu Hoi, Hue");
        return id;
    }

    private static Map<String, Object> form() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("identity", identity());
        form.put("phone", "0912345678");
        form.put("allergies", Map.of("none", false, "items", List.of("PENICILLIN", "OTHER"), "otherText", "Kiwi fruit XYZ"));
        form.put("medicines", Map.of("none", false, "text", "Metformin 500 SECRETMED"));
        form.put("reason", Map.of("code", "PAIN", "text", "Toothache lower left SECRETWHY"));
        form.put("consent", true);
        return form;
    }

    private static Map<String, Object> with(Map<String, Object> form, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(form);
        if (value == null) {
            copy.remove(key);
        } else {
            copy.put(key, value);
        }
        return copy;
    }

    private String code(ResponseEntity<String> response) {
        return json(response).path("code").asText();
    }

    private void assertInvalid(String token, Map<String, Object> body) {
        ResponseEntity<String> response = submit(token, body);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("DENTAL_INVALID_FORM");
        assertThat(response.getBody()).doesNotContain("SECRETMED").doesNotContain("SECRETWHY")
            .doesNotContain("079090123456").doesNotContain("0912345678");
    }

    @Test
    void summary_has_no_identity_or_health_values() {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> response = summary(v.token());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = data(response);
        assertThat(data.path("open").asBoolean()).isTrue();
        assertThat(data.path("submitted").asBoolean()).isTrue();
        assertThat(data.path("child").asBoolean()).isFalse();
        assertThat(data.path("returning").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("maskedName").asText()).isNotBlank().doesNotContain("Tran Thi Binh");
        assertThat(response.getBody()).doesNotContain("079090123456").doesNotContain("SECRETMED")
            .doesNotContain("SECRETWHY").doesNotContain("PENICILLIN").doesNotContain("1990-05-17").doesNotContain("0912345678");
    }

    @Test
    void summary_marks_a_child_by_the_patient_record() {
        Visit child = visit(LocalDate.now().minusYears(8));
        Visit adult = adult();

        assertThat(data(summary(child.token())).path("child").asBoolean()).isTrue();
        assertThat(data(summary(adult.token())).path("child").asBoolean()).isFalse();
        assertThat(data(summary(child.token())).path("returning").asText()).isEqualTo("NONE");
    }

    @Test
    void submit_and_replace_form() {
        Visit v = adult();

        ResponseEntity<String> first = submit(v.token(), form());
        Map<String, Object> changed = with(form(), "phone", "0987654321");
        ResponseEntity<String> second = submit(v.token(), changed);

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(200);
        assertThat(count("previsit_form", "appointment_id", v.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phone FROM dental.previsit_form WHERE appointment_id = ?",
            String.class, v.id())).isEqualTo("+84987654321");
        assertThat(jdbc.queryForObject("SELECT notice_version FROM dental.previsit_form WHERE appointment_id = ?",
            String.class, v.id())).isEqualTo("2026-11-14");
        assertThat(jdbc.queryForObject("SELECT consent_at IS NOT NULL FROM dental.previsit_form WHERE appointment_id = ?",
            Boolean.class, v.id())).isTrue();
    }

    @Test
    void blank_allergies_or_medicines_400() {
        Visit v = adult();
        assertInvalid(v.token(), with(form(), "allergies", null));
        assertInvalid(v.token(), with(form(), "allergies", Map.of("none", false)));
        assertInvalid(v.token(), with(form(), "allergies", Map.of("none", false, "items", List.of())));
        assertInvalid(v.token(), with(form(), "medicines", null));
        assertInvalid(v.token(), with(form(), "medicines", Map.of("none", false, "text", "  ")));
        assertInvalid(v.token(), with(form(), "medicines", Map.of("none", false)));
        assertInvalid(v.token(), with(form(), "allergies", Map.of("none", false, "items", List.of("OTHER"))));
        assertInvalid(v.token(), with(form(), "reason", Map.of("code", "OTHER")));
        assertThat(count("previsit_form", "appointment_id", v.id())).isZero();
    }

    @Test
    void none_and_items_together_400() {
        Visit v = adult();
        assertInvalid(v.token(), with(form(), "allergies", Map.of("none", true, "items", List.of("LATEX"))));
        assertInvalid(v.token(), with(form(), "medicines", Map.of("none", true, "text", "SECRETMED")));
        Map<String, Object> ok = with(with(form(), "allergies", Map.of("none", true)),
            "medicines", Map.of("none", true));
        assertThat(submit(v.token(), ok).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void child_without_parent_400() {
        Visit child = visit(LocalDate.now().minusYears(8));
        assertInvalid(child.token(), with(form(), "identity", null));
        assertInvalid(child.token(), with(with(form(), "identity", null), "parentName", "Tran Van Cha"));
        assertInvalid(child.token(), with(with(form(), "identity", null), "parentPhone", "0912345678"));
        Map<String, Object> ok = with(with(with(form(), "identity", null), "parentName", "Tran Van Cha"),
            "parentPhone", "0912345678");
        assertThat(submit(child.token(), ok).getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT parent_phone FROM dental.previsit_form WHERE appointment_id = ?",
            String.class, child.id())).isEqualTo("+84912345678");
    }

    @Test
    void adult_with_parent_fields_400() {
        Visit v = adult();
        assertInvalid(v.token(), with(with(form(), "parentName", "Tran Van Cha"), "parentPhone", "0912345678"));
        assertInvalid(v.token(), with(form(), "identity", null));
    }

    @Test
    void consent_required_400() {
        Visit v = adult();
        assertInvalid(v.token(), with(form(), "consent", false));
        assertInvalid(v.token(), with(form(), "consent", null));
        assertInvalid(v.token(), with(form(), "phone", "12345"));
    }

    @Test
    void health_columns_encrypted_at_rest() {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT allergies_enc, medicines_enc, reason_enc FROM dental.previsit_form WHERE appointment_id = ?", v.id());

        for (String column : List.of("allergies_enc", "medicines_enc", "reason_enc")) {
            byte[] raw = (byte[]) row.get(column);
            assertThat(raw).as(column).isNotNull();
            String text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String plain : List.of("PENICILLIN", "Kiwi", "Metformin", "SECRETMED", "Toothache", "SECRETWHY", "PAIN", "CHECKUP")) {
                assertThat(text).as(column + " has no " + plain).doesNotContain(plain);
            }
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM dental.previsit_form WHERE appointment_id = ?
              AND (convert_from(allergies_enc, 'LATIN1') LIKE '%PENICILLIN%' OR convert_from(reason_enc, 'LATIN1') LIKE '%SECRETWHY%')
            """, Integer.class, v.id())).isZero();
    }

    @Test
    void previous_returns_answers_after_dob_match() {
        Visit first = adult();
        assertThat(submit(first.token(), form()).getStatusCode().value()).isEqualTo(201);
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM dental.appointment WHERE id = ?", UUID.class, first.id());
        // Close the first visit, then a second appointment of the same patient
        jdbc.update("UPDATE dental.appointment SET status = 'ARRIVED' WHERE id = ?", first.id());
        Visit second = adult();
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, second.id());
        jdbc.update("UPDATE dental.patient SET date_of_birth = '1990-05-17' WHERE id = ?", patient);

        ResponseEntity<String> earlier = previous(second.token(), "1990-05-17");

        assertThat(earlier.getStatusCode().value()).as(earlier.getBody()).isEqualTo(200);
        JsonNode data = data(earlier);
        assertThat(data.path("fromCurrentAppointment").asBoolean()).isFalse();
        assertThat(data.path("allergies").path("items").toString()).contains("PENICILLIN");
        assertThat(data.path("allergies").path("otherText").asText()).isEqualTo("Kiwi fruit XYZ");
        assertThat(data.path("medicines").path("text").asText()).contains("Metformin");
        assertThat(data.path("reason").path("code").asText()).isEqualTo("PAIN");
        assertThat(earlier.getBody()).doesNotContain("079090123456").doesNotContain("TRAN THI BINH");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "allergies", "medicines", "reason", "fromCurrentAppointment");

        // Own answers come first once submitted
        Map<String, Object> mine = with(form(), "medicines", Map.of("none", true));
        assertThat(submit(second.token(), mine).getStatusCode().value()).isEqualTo(201);
        JsonNode own = data(previous(second.token(), "1990-05-17"));
        assertThat(own.path("fromCurrentAppointment").asBoolean()).isTrue();
        assertThat(own.path("medicines").path("none").asBoolean()).isTrue();
    }

    @Test
    void forwarded_link_shows_nothing_and_locks_after_five() {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);

        for (int i = 1; i <= 4; i++) {
            ResponseEntity<String> wrong = previous(v.token(), "1991-01-0" + i);
            assertThat(wrong.getStatusCode().value()).as("attempt " + i).isEqualTo(403);
            assertThat(code(wrong)).isEqualTo("DENTAL_CHECK_FAILED");
            assertThat(wrong.getBody()).doesNotContain("SECRETMED").doesNotContain("PENICILLIN");
        }
        assertThat(data(summary(v.token())).path("returning").asText()).isEqualTo("AVAILABLE");
        ResponseEntity<String> fifth = previous(v.token(), "1991-01-05");
        assertThat(fifth.getStatusCode().value()).isEqualTo(423);
        assertThat(code(fifth)).isEqualTo("DENTAL_CHECK_LOCKED");
        assertThat(data(summary(v.token())).path("returning").asText()).isEqualTo("LOCKED");

        ResponseEntity<String> right = previous(v.token(), "1990-05-17");
        assertThat(right.getStatusCode().value()).as("the right date after lockout").isEqualTo(423);
        assertThat(right.getBody()).doesNotContain("SECRETMED").doesNotContain("PENICILLIN").doesNotContain("Kiwi");
        assertThat(jdbc.queryForObject("SELECT dob_attempts FROM dental.visit_link WHERE appointment_id = ?",
            Integer.class, v.id())).isEqualTo(5);
        // The form can still be submitted from scratch
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void bad_tokens_are_the_same_404_on_every_route() {
        Visit v = adult();
        Map<String, Object> dob = Map.of("dateOfBirth", "1990-05-17");
        for (String token : new String[] {null, "", "nope", "x".repeat(200), v.token() + "A"}) {
            assertThat(exchange(HttpMethod.GET, PUBLIC, null, null, HEADER, token).getStatusCode().value()).isEqualTo(404);
            assertThat(exchange(HttpMethod.POST, PUBLIC + "/form", null, form(), HEADER, token).getStatusCode().value()).isEqualTo(404);
            assertThat(exchange(HttpMethod.POST, PUBLIC + "/previous", null, dob, HEADER, token).getStatusCode().value()).isEqualTo(404);
        }
        // The token is never accepted from the path or the query
        assertThat(exchange(HttpMethod.GET, PUBLIC + "?token=" + v.token(), null, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void closed_after_cancel_arrive_noshow_409() {
        for (String status : List.of("CANCELLED", "ARRIVED", "NO_SHOW")) {
            Visit v = adult();
            jdbc.update("UPDATE dental.appointment SET status = ? WHERE id = ?", status, v.id());

            ResponseEntity<String> summary = summary(v.token());
            assertThat(summary.getStatusCode().value()).as(status).isEqualTo(200);
            assertThat(data(summary).path("open").asBoolean()).isFalse();
            ResponseEntity<String> form = submit(v.token(), form());
            assertThat(form.getStatusCode().value()).as(status).isEqualTo(409);
            assertThat(code(form)).isEqualTo("DENTAL_VISIT_CLOSED");
            assertThat(previous(v.token(), "1990-05-17").getStatusCode().value()).isEqualTo(409);
            ResponseEntity<String> scan = upload(v.token(), CccdQrImages.jpeg(CccdQrImages.payload(), 1));
            assertThat(scan.getStatusCode().value()).isEqualTo(409);
            assertThat(code(scan)).isEqualTo("DENTAL_VISIT_CLOSED");
            assertThat(count("previsit_form", "appointment_id", v.id())).isZero();
        }
    }

    @Test
    void cancel_deletes_submitted_form_and_photo() {
        Visit v = adult();
        ResponseEntity<String> scan = upload(v.token(), CccdQrImages.jpeg(CccdQrImages.payload(), 2));
        assertThat(scan.getStatusCode().value()).as(scan.getBody()).isEqualTo(201);
        String scanId = data(scan).path("scanId").asText();
        assertThat(submit(v.token(), with(form(), "idScanId", scanId)).getStatusCode().value()).isEqualTo(201);
        assertThat(count("previsit_form", "appointment_id", v.id())).isEqualTo(1);
        assertThat(count("id_scan", "appointment_id", v.id())).isEqualTo(1);

        assertThat(post(APPOINTMENTS + "/" + v.id() + "/cancel", v.staff(), null).getStatusCode().value()).isEqualTo(200);

        assertThat(count("previsit_form", "appointment_id", v.id())).isZero();
        assertThat(count("id_scan", "appointment_id", v.id())).isZero();
        assertThat(data(summary(v.token())).path("open").asBoolean()).isFalse();
    }

    @Test
    void reschedule_keeps_form_and_moves_link_expiry() {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);
        JsonNode view = data(get(APPOINTMENTS + "/" + v.id(), v.staff()));
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("startsAt", at(LocalDate.parse(view.path("startsAt").asText().substring(0, 10)).plusDays(5), 15, 0));

        ResponseEntity<String> moved = patch(APPOINTMENTS + "/" + v.id(), v.staff(), change, view.path("version").asText());

        assertThat(moved.getStatusCode().value()).as(moved.getBody()).isEqualTo(200);
        assertThat(count("previsit_form", "appointment_id", v.id())).isEqualTo(1);
        JsonNode after = data(summary(v.token()));
        assertThat(after.path("submitted").asBoolean()).isTrue();
        assertThat(after.path("open").asBoolean()).isTrue();
        assertThat(submit(v.token(), form()).getStatusCode().value()).as("link still works, form replaced").isEqualTo(200);
    }

    @Test
    void edited_fields_against_qr() {
        Visit v = adult();
        ResponseEntity<String> scan = upload(v.token(), CccdQrImages.jpeg(CccdQrImages.payload(), 3));
        assertThat(scan.getStatusCode().value()).as(scan.getBody()).isEqualTo(201);
        JsonNode fields = data(scan).path("fields");
        assertThat(fields.path("idNumber").asText()).isEqualTo(CccdQrImages.ID_NUMBER);
        String scanId = data(scan).path("scanId").asText();

        Map<String, Object> same = new LinkedHashMap<>();
        fields.fields().forEachRemaining(e -> same.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
        assertThat(submit(v.token(), with(with(form(), "identity", same), "idScanId", scanId)).getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT edited_fields FROM dental.previsit_form WHERE appointment_id = ?",
            String.class, v.id())).as("untouched").isNull();

        Map<String, Object> edited = new LinkedHashMap<>(same);
        edited.put("address", "99 Another Street, Hue");
        edited.put("fullName", "NGUYEN VAN BAO");
        assertThat(submit(v.token(), with(with(form(), "identity", edited), "idScanId", scanId)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT edited_fields FROM dental.previsit_form WHERE appointment_id = ?",
            String.class, v.id())).isEqualTo("fullName,address");

        // A scan of another appointment is refused
        Visit other = adult();
        ResponseEntity<String> foreign = submit(other.token(), with(form(), "idScanId", scanId));
        assertThat(foreign.getStatusCode().value()).isEqualTo(400);
        assertThat(code(foreign)).isEqualTo("DENTAL_INVALID_FORM");
    }

    @Test
    void no_store_and_noindex() {
        Visit v = adult();
        List<ResponseEntity<String>> responses = List.of(
            summary(v.token()), summary("nope"), submit(v.token(), form()), submit(v.token(), with(form(), "consent", false)),
            previous(v.token(), "1991-01-01"));
        for (ResponseEntity<String> response : responses) {
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
            assertThat(response.getHeaders().getFirst("X-Robots-Tag")).contains("noindex");
        }
    }

    @Test
    void audit_has_no_values() {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(200);
        previous(v.token(), "1991-01-01");

        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT op, entity_type, user_id, source_ip, coalesce(before_json::text, '') AS before,
                   coalesce(after_json::text, '') AS after
            FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op = 'PREVISIT_SUBMIT'
            """, v.id());

        assertThat(events).hasSize(2);
        for (Map<String, Object> event : events) {
            assertThat(event.get("entity_type")).isEqualTo("Appointment");
            assertThat(event.get("user_id")).isNull();
            assertThat(event.get("before").toString() + event.get("after")).isEmpty();
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event WHERE after_json::text LIKE ANY (ARRAY['%079090123456%', '%SECRETMED%',
              '%PENICILLIN%', '%' || ? || '%'])
            """, Integer.class, v.token())).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> upload(String token, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "cccd.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER, token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(PUBLIC + "/id-scans", new HttpEntity<>(body, headers), String.class);
    }
}
