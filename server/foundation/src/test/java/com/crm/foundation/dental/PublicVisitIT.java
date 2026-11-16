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
              AND (position(convert_to('PENICILLIN', 'UTF8') in allergies_enc) > 0 OR position(convert_to('SECRETWHY', 'UTF8') in reason_enc) > 0)
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
        // D22: an earlier visit's reason is never returned
        assertThat(data.path("reason").isMissingNode() || data.path("reason").isNull()).as("reason of an earlier visit").isTrue();
        assertThat(earlier.getBody()).doesNotContain("PAIN").doesNotContain("SECRETWHY")
            .doesNotContain("079090123456").doesNotContain("TRAN THI BINH");
        assertThat(data.fieldNames()).toIterable().contains("allergies", "medicines", "fromCurrentAppointment")
            .doesNotContain("identity", "idNumber", "fullName");

        // Own answers come first once submitted
        Map<String, Object> mine = with(form(), "medicines", Map.of("none", true));
        assertThat(submit(second.token(), mine).getStatusCode().value()).isEqualTo(201);
        JsonNode own = data(previous(second.token(), "1990-05-17"));
        assertThat(own.path("fromCurrentAppointment").asBoolean()).isTrue();
        assertThat(own.path("medicines").path("none").asBoolean()).isTrue();
        // ... and the current appointment's own form returns its own reason
        assertThat(own.path("reason").path("code").asText()).isEqualTo("PAIN");
        assertThat(own.path("reason").path("text").asText()).contains("SECRETWHY");
    }

    @Test
    void previous_with_several_earlier_forms_returns_the_latest_by_submitted_at() {
        Visit oldest = adult();
        assertThat(submit(oldest.token(), with(form(), "medicines", Map.of("none", false, "text", "OLDEST-MED"))).getStatusCode().value()).isEqualTo(201);
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM dental.appointment WHERE id = ?", UUID.class, oldest.id());
        Visit newest = adult();
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, newest.id());
        assertThat(submit(newest.token(), with(form(), "medicines", Map.of("none", false, "text", "NEWEST-MED"))).getStatusCode().value()).isEqualTo(201);
        // Inserted last but not newest: the order must come from submitted_at, not from insertion
        jdbc.update("UPDATE dental.previsit_form SET submitted_at = now() - interval '5 days' WHERE appointment_id = ?", oldest.id());
        jdbc.update("UPDATE dental.previsit_form SET submitted_at = now() - interval '2 days' WHERE appointment_id = ?", newest.id());
        Visit middle = adult();
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, middle.id());
        assertThat(submit(middle.token(), with(form(), "medicines", Map.of("none", false, "text", "MIDDLE-MED"))).getStatusCode().value()).isEqualTo(201);
        jdbc.update("UPDATE dental.previsit_form SET submitted_at = now() - interval '3 days' WHERE appointment_id = ?", middle.id());
        jdbc.update("UPDATE dental.appointment SET status = 'ARRIVED' WHERE id IN (?, ?, ?)", oldest.id(), newest.id(), middle.id());
        Visit today = adult();
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, today.id());
        jdbc.update("UPDATE dental.patient SET date_of_birth = '1990-05-17' WHERE id = ?", patient);

        ResponseEntity<String> response = previous(today.token(), "1990-05-17");

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(data(response).path("fromCurrentAppointment").asBoolean()).isFalse();
        assertThat(data(response).path("medicines").path("text").asText()).isEqualTo("NEWEST-MED");
    }

    @Test
    void previous_audits_the_match_and_the_lockout_without_values() {
        Visit first = adult();
        assertThat(submit(first.token(), form()).getStatusCode().value()).isEqualTo(201);
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM dental.appointment WHERE id = ?", UUID.class, first.id());
        jdbc.update("UPDATE dental.appointment SET status = 'ARRIVED' WHERE id = ?", first.id());
        Visit second = adult();
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, second.id());
        jdbc.update("UPDATE dental.patient SET date_of_birth = '1990-05-17' WHERE id = ?", patient);

        // Wrong dates 1-4 leave no audit row; the 5th locks and leaves exactly one
        for (int i = 1; i <= 4; i++) {
            assertThat(previous(second.token(), "1991-01-0" + i).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(patientAudits(second.id())).isEmpty();
        assertThat(previous(second.token(), "1991-01-05").getStatusCode().value()).isEqualTo(423);
        List<Map<String, Object>> locked = patientAudits(second.id());
        assertThat(locked).hasSize(1);
        assertThat(locked.get(0)).containsEntry("op", "PREVISIT_CHECK_LOCKED").containsEntry("entity_type", "Appointment");
        assertThat(locked.get(0).get("user_id")).isNull();
        assertThat(locked.get(0).get("before").toString() + locked.get(0).get("after")).isEmpty();
        // A locked link refuses even the right date and does not audit again
        assertThat(previous(second.token(), "1990-05-17").getStatusCode().value()).isEqualTo(423);
        assertThat(patientAudits(second.id())).hasSize(1);

        // A match on another link: one PREVIOUS_VIEW naming the form the answers came from, nothing else
        Visit third = adult();
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, third.id());
        assertThat(previous(third.token(), "1990-05-17").getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> views = patientAudits(third.id());
        assertThat(views).hasSize(1);
        Map<String, Object> view = views.get(0);
        assertThat(view).containsEntry("op", "PREVISIT_PREVIOUS_VIEW").containsEntry("entity_type", "Appointment");
        assertThat(view.get("user_id")).isNull();
        assertThat(view.get("before")).isEqualTo("");
        assertThat(jdbc.queryForObject("SELECT after_json->>'sourceAppointmentId' FROM audit_event WHERE id = ?",
            String.class, view.get("id"))).isEqualTo(first.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jsonb_object_keys((SELECT after_json FROM audit_event WHERE id = ?))",
            Integer.class, view.get("id"))).isEqualTo(1);

        // No answer or token reaches any of these rows, and no date of birth (the locked row is empty, the view row
        // holds exactly the one key above)
        assertThat(locked.get(0).get("after")).isEqualTo("");
        List<Map<String, Object>> all = new java.util.ArrayList<>(locked);
        all.addAll(views);
        for (Map<String, Object> row : all) {
            String text = row.get("before").toString() + row.get("after");
            assertThat(text).doesNotContain("PENICILLIN").doesNotContain("SECRETMED").doesNotContain("SECRETWHY")
                .doesNotContain("Kiwi").doesNotContain(second.token()).doesNotContain(third.token());
        }
    }

    private List<Map<String, Object>> patientAudits(UUID appointmentId) {
        return jdbc.queryForList("""
            SELECT id, op, entity_type, user_id, coalesce(before_json::text, '') AS before,
                   coalesce(after_json::text, '') AS after
            FROM audit_event
            WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op IN ('PREVISIT_PREVIOUS_VIEW', 'PREVISIT_CHECK_LOCKED')
            ORDER BY occurred_at
            """, appointmentId);
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
            assertThat(upload(token, CccdQrImages.jpeg(CccdQrImages.payload(), 1)).getStatusCode().value()).as("id-scans").isEqualTo(404);
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
        // Staff no longer see either
        assertThat(get(APPOINTMENTS + "/" + v.id() + "/previsit", v.staff()).getStatusCode().value()).isEqualTo(404);
        assertThat(get(APPOINTMENTS + "/" + v.id() + "/previsit/id-scan", v.staff()).getStatusCode().value()).isEqualTo(404);
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
        Visit locked = adult();
        assertThat(submit(locked.token(), form()).getStatusCode().value()).isEqualTo(201);
        jdbc.update("UPDATE dental.visit_link SET dob_attempts = 5 WHERE appointment_id = ?", locked.id());
        Visit closed = adult();
        jdbc.update("UPDATE dental.appointment SET status = 'CANCELLED' WHERE id = ?", closed.id());
        ResponseEntity<String> uploaded = upload(v.token(), CccdQrImages.jpeg(CccdQrImages.payload(), 7));
        assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> lockedResponse = previous(locked.token(), "1990-05-17");
        ResponseEntity<String> closedResponse = submit(closed.token(), form());
        assertThat(lockedResponse.getStatusCode().value()).isEqualTo(423);
        assertThat(closedResponse.getStatusCode().value()).isEqualTo(409);
        List<ResponseEntity<String>> responses = List.of(
            summary(v.token()), summary("nope"), submit(v.token(), form()), submit(v.token(), with(form(), "consent", false)),
            previous(v.token(), "1991-01-01"), uploaded, lockedResponse, closedResponse,
            upload("nope", CccdQrImages.jpeg(CccdQrImages.payload(), 8)));
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
              OR before_json::text LIKE ANY (ARRAY['%079090123456%', '%SECRETMED%', '%PENICILLIN%', '%' || ? || '%'])
            """, Integer.class, v.token(), v.token())).isZero();
    }


    // ── hardening ────────────────────────────────────────────────────────────────────────────────

    /** Runs {@code count} copies of {@code call} released together by a barrier. */
    private <T> List<T> parallel(int count, java.util.concurrent.Callable<T> call) throws Exception {
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(count);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(count);
        try {
            List<java.util.concurrent.Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<T> results = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<T> future : futures) {
                results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void previous_parallel_wrong_guesses_stop_at_five() throws Exception {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);

        List<ResponseEntity<String>> responses = parallel(10, () -> previous(v.token(), "1991-01-01"));

        assertThat(responses).extracting(r -> r.getStatusCode().value()).containsOnly(403, 423);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 403).count()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT dob_attempts FROM dental.visit_link WHERE appointment_id = ?",
            Integer.class, v.id())).isEqualTo(5);
    }

    @Test
    void parallel_submit_one_row() throws Exception {
        Visit v = adult();

        List<ResponseEntity<String>> responses = parallel(3, () -> submit(v.token(), form()));

        assertThat(responses).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 200, 200);
        assertThat(count("previsit_form", "appointment_id", v.id())).isEqualTo(1);
    }

    @Test
    void revoked_between_resolve_and_lock_is_404() {
        Visit v = adult();
        assertThat(submit(v.token(), form()).getStatusCode().value()).isEqualTo(201);
        byte[] oldHash = sha256(v.token());
        assertThat(storePrevious(v.id(), oldHash, "1990-05-17")).as("live link").isEqualTo(200);

        // Revoked after the request resolved its token
        assertThat(exchange(HttpMethod.DELETE, linkPath(v.id()), v.staff(), null).getStatusCode().value()).isEqualTo(204);
        assertThat(storePrevious(v.id(), oldHash, "1990-05-17")).isEqualTo(404);
        assertThat(storeScan(v.id(), oldHash)).as("scan store").isEqualTo(404);

        // Replaced: the old hash is refused and the new link's counter is untouched
        String fresh = data(post(linkPath(v.id()), v.staff(), null)).path("url").asText().substring(BASE.length());
        assertThat(storePrevious(v.id(), oldHash, "1991-01-01")).isEqualTo(404);
        assertThat(storeScan(v.id(), oldHash)).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT dob_attempts FROM dental.visit_link WHERE appointment_id = ?",
            Integer.class, v.id())).isZero();
        assertThat(storePrevious(v.id(), sha256(fresh), "1991-01-01")).as("the matched link counts").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT dob_attempts FROM dental.visit_link WHERE appointment_id = ?",
            Integer.class, v.id())).isEqualTo(1);

        // Expired
        jdbc.update("UPDATE dental.visit_link SET expires_at = now() - interval '1 hour' WHERE appointment_id = ?", v.id());
        assertThat(storePrevious(v.id(), sha256(fresh), "1990-05-17")).isEqualTo(404);
    }

    @Test
    void no_answers_means_404_without_counting() {
        Visit v = adult();

        assertThat(previous(v.token(), "1991-01-01").getStatusCode().value()).isEqualTo(404);
        assertThat(previous(v.token(), "1990-05-17").getStatusCode().value()).as("right date too").isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT dob_attempts FROM dental.visit_link WHERE appointment_id = ?",
            Integer.class, v.id())).isZero();
        assertThat(data(summary(v.token())).path("returning").asText()).isEqualTo("NONE");
    }

    @Test
    void nul_in_text_is_400() {
        Visit v = adult();
        assertInvalid(v.token(), with(form(), "medicines", Map.of("none", false, "text", "aspirin\u0000 SECRETMED")));
        Map<String, Object> id = identity();
        id.put("address", "12 Le Loi\u0000");
        assertInvalid(v.token(), with(form(), "identity", id));
        assertThat(count("previsit_form", "appointment_id", v.id())).isZero();
    }

    @Test
    void copied_ciphertext_fails_closed() {
        Visit a = adult();
        Visit b = adult();
        assertThat(submit(a.token(), form()).getStatusCode().value()).isEqualTo(201);
        assertThat(submit(b.token(), with(form(), "allergies", Map.of("none", true))).getStatusCode().value()).isEqualTo(201);
        jdbc.update("""
            UPDATE dental.previsit_form SET allergies_enc = (SELECT allergies_enc FROM dental.previsit_form WHERE appointment_id = ?)
            WHERE appointment_id = ?
            """, a.id(), b.id());

        ResponseEntity<String> response = previous(b.token(), "1990-05-17");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(code(response)).isEqualTo("DENTAL_FIELD_UNREADABLE");
        assertThat(response.getBody()).doesNotContain("Kiwi").doesNotContain("PENICILLIN");
        // The untouched columns of the same row still belong to it
        assertThat(previous(a.token(), "1990-05-17").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void scan_of_same_patients_other_appointment_400() {
        Visit a = adult();
        Visit b = adult();
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM dental.appointment WHERE id = ?", UUID.class, a.id());
        jdbc.update("UPDATE dental.appointment SET patient_id = ? WHERE id = ?", patient, b.id());
        ResponseEntity<String> scan = upload(a.token(), CccdQrImages.jpeg(CccdQrImages.payload(), 9));
        assertThat(scan.getStatusCode().value()).as(scan.getBody()).isEqualTo(201);

        ResponseEntity<String> response = submit(b.token(), with(form(), "idScanId", data(scan).path("scanId").asText()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("DENTAL_INVALID_FORM");
        assertThat(count("previsit_form", "appointment_id", b.id())).isZero();
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The store's locked check, called directly with a token hash the request "resolved" earlier. */
    private int storePrevious(UUID appointment, byte[] hash, String dob) {
        return storeStatus("previsitStore", "previous", new Class<?>[] {UUID.class, byte[].class, LocalDate.class},
            appointment, hash, LocalDate.parse(dob));
    }

    private int storeScan(UUID appointment, byte[] hash) {
        return storeStatus("dentalIdScanStore", "store",
            new Class<?>[] {UUID.class, byte[].class, byte[].class, String.class, byte[].class, String.class},
            appointment, hash, new byte[32], "image/jpeg", new byte[] {1, 2, 3}, null);
    }

    private int storeStatus(String beanName, String method, Class<?>[] types, Object... args) {
        Object store = bean(beanName);
        try {
            Class<?> type = store.getClass();
            java.lang.reflect.Method found = null;
            while (type != null && found == null) {
                try {
                    found = type.getDeclaredMethod(method, types);
                } catch (NoSuchMethodException e) {
                    type = type.getSuperclass();
                }
            }
            assertThat(found).as(beanName + "." + method).isNotNull();
            found.setAccessible(true);
            found.invoke(store, args);
            return 200;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof com.crm.foundation.Plugin.PluginProblem problem) {
                return problem.status();
            }
            // A wrong date is an outcome, not a problem; anything else is a bug
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
        if (token != null) {
            headers.set(HEADER, token);
        }
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(PUBLIC + "/id-scans", new HttpEntity<>(body, headers), String.class);
    }
}
