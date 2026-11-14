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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** What staff do with a submitted pre-visit form: read it, see the photo, apply it, find it on the day list. */
class PrevisitStaffIT extends VisitLinkITSupport {

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

    private record Visit(String staff, UUID id, String token, UUID patientId) {
    }

    private Visit visitOn(LocalDate day, int hour) {
        String staff = adminToken();
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("fullName", "Old Name " + tag());
        patient.put("phone", "09" + (10_000_000 + ThreadLocalRandom.current().nextLong(89_000_000)));
        patient.put("dateOfBirth", "1990-05-17");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newPatient", patient);
        body.put("startsAt", day.atTime(hour, 0).atOffset(CLINIC));
        ResponseEntity<String> booked = post(APPOINTMENTS, staff, body);
        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        UUID id = idOf(data(booked));
        ResponseEntity<String> link = post(linkPath(id), staff, null);
        assertThat(link.getStatusCode().value()).as(link.getBody()).isEqualTo(201);
        return new Visit(staff, id, data(link).path("url").asText().substring(BASE.length()),
            UUID.fromString(data(booked).path("patientId").asText()));
    }

    private Visit visit() {
        return visitOn(futureDay(), 10);
    }

    private static Map<String, Object> identity() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("idType", "CCCD");
        id.put("idNumber", "079090123456");
        id.put("fullName", "Trần Thị Bình");
        id.put("dateOfBirth", "1990-05-18");
        id.put("nationality", "VN");
        id.put("address", "12 Le Loi, Phu Hoi, Hue");
        return id;
    }

    private static Map<String, Object> form() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("identity", identity());
        form.put("phone", "0912345678");
        form.put("allergies", Map.of("none", false, "items", List.of("PENICILLIN", "OTHER"), "otherText", "Kiwi\nfruit XYZ"));
        form.put("medicines", Map.of("none", false, "text", "Metformin 500\n\tSECRETMED"));
        form.put("reason", Map.of("code", "PAIN", "text", "Toothache lower left SECRETWHY"));
        form.put("consent", true);
        return form;
    }

    private ResponseEntity<String> submit(Visit v, Map<String, Object> body) {
        ResponseEntity<String> response = exchange(HttpMethod.POST, PUBLIC + "/form", null, body, HEADER, v.token());
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        return response;
    }

    private ResponseEntity<String> upload(Visit v, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "cccd.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER, v.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(PUBLIC + "/id-scans", new HttpEntity<>(body, headers), String.class);
    }

    private static String previsitPath(UUID id) {
        return APPOINTMENTS + "/" + id + "/previsit";
    }

    private int views(UUID appointment) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op = 'PREVISIT_VIEW'",
            Integer.class, appointment);
    }

    private ResponseEntity<String> apply(Visit v, String token, String ifMatch) {
        List<Long> versions = jdbc.queryForList("SELECT version FROM dental.previsit_form WHERE appointment_id = ?",
            Long.class, v.id());
        return apply(v, token, ifMatch, versions.isEmpty() ? 0L : versions.get(0));
    }

    private ResponseEntity<String> apply(Visit v, String token, String ifMatch, long formVersion) {
        return exchange(HttpMethod.POST, previsitPath(v.id()) + "/apply", token, Map.of("formVersion", formVersion),
            "If-Match", ifMatch);
    }

    private String code(ResponseEntity<String> response) {
        return json(response).path("code").asText();
    }

    @Test
    void staff_reads_decrypted_form_and_view_is_audited() {
        Visit v = visit();
        assertThat(get(previsitPath(v.id()), v.staff()).getStatusCode().value()).as("no form yet").isEqualTo(404);
        assertThat(views(v.id())).as("a 404 is not audited").isZero();
        submit(v, form());

        ResponseEntity<String> response = get(previsitPath(v.id()), v.staff());

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        JsonNode data = data(response);
        assertThat(data.path("identity").path("fullName").asText()).isEqualTo("Trần Thị Bình");
        assertThat(data.path("identity").path("idNumber").asText()).isEqualTo("079090123456");
        assertThat(data.path("identity").path("dateOfBirth").asText()).isEqualTo("1990-05-18");
        assertThat(data.path("identity").path("address").asText()).isEqualTo("12 Le Loi, Phu Hoi, Hue");
        assertThat(data.path("phone").asText()).isEqualTo("+84912345678");
        assertThat(data.path("allergies").path("none").asBoolean()).isFalse();
        assertThat(data.path("allergies").path("items")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("PENICILLIN", "OTHER");
        assertThat(data.path("allergies").path("otherText").asText()).isEqualTo("Kiwi\nfruit XYZ");
        assertThat(data.path("medicines").path("text").asText()).isEqualTo("Metformin 500\n\tSECRETMED");
        assertThat(data.path("reason").path("code").asText()).isEqualTo("PAIN");
        assertThat(data.path("reason").path("text").asText()).isEqualTo("Toothache lower left SECRETWHY");
        assertThat(data.path("editedFields").isArray()).isTrue();
        assertThat(data.path("consentAt").asText()).isNotBlank();
        assertThat(data.path("submittedAt").asText()).isNotBlank();
        assertThat(data.path("patientVersion").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(data.path("hasIdScan").asBoolean()).isFalse();
        assertThat(views(v.id())).isEqualTo(1);

        get(previsitPath(v.id()), v.staff());
        assertThat(views(v.id())).isEqualTo(2);
        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT user_id, entity_type, coalesce(before_json::text, '') AS before, coalesce(after_json::text, '') AS after
            FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op = 'PREVISIT_VIEW'
            """, v.id());
        for (Map<String, Object> event : events) {
            assertThat(event.get("user_id")).as("the staff user").isNotNull();
            assertThat(event.get("entity_type")).isEqualTo("Appointment");
            assertThat(event.get("before").toString() + event.get("after")).isEmpty();
        }
    }

    @Test
    void unreadable_answers_are_500_with_a_fixed_message_and_no_audit() {
        Visit a = visit();
        Visit b = visit();
        submit(a, form());
        submit(b, form());
        jdbc.update("""
            UPDATE dental.previsit_form SET medicines_enc = (SELECT medicines_enc FROM dental.previsit_form WHERE appointment_id = ?)
            WHERE appointment_id = ?
            """, a.id(), b.id());

        ResponseEntity<String> response = get(previsitPath(b.id()), b.staff());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(code(response)).isEqualTo("DENTAL_FIELD_UNREADABLE");
        assertThat(response.getBody()).contains("The stored answers could not be read").doesNotContain("SECRETMED");
        assertThat(views(b.id())).isZero();
        assertThat(get(previsitPath(a.id()), a.staff()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void photo_visible_before_visit() {
        Visit v = visit();
        Visit other = visit();
        ResponseEntity<String> scan = upload(v, CccdQrImages.jpeg(CccdQrImages.payload(), 4));
        assertThat(scan.getStatusCode().value()).as(scan.getBody()).isEqualTo(201);
        Map<String, Object> body = form();
        body.put("idScanId", data(scan).path("scanId").asText());
        assertThat(get(previsitPath(v.id()) + "/id-scan", v.staff()).getStatusCode().value()).as("no form yet").isEqualTo(404);
        submit(v, body);
        submit(other, form());

        ResponseEntity<byte[]> image = rest.exchange(previsitPath(v.id()) + "/id-scan", HttpMethod.GET, bearer(v.staff()), byte[].class);

        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(image.getHeaders().getCacheControl()).contains("no-store");
        assertThat(image.getBody()).hasSizeGreaterThan(100);
        assertThat(image.getBody()[0]).isEqualTo((byte) 0xFF);
        assertThat(image.getBody()[1]).isEqualTo((byte) 0xD8);
        assertThat(data(get(previsitPath(v.id()), v.staff())).path("hasIdScan").asBoolean()).isTrue();
        assertThat(get(previsitPath(other.id()) + "/id-scan", other.staff()).getStatusCode().value())
            .as("a form without a photo").isEqualTo(404);
        assertThat(get(APPOINTMENTS + "/" + UUID.randomUUID() + "/previsit/id-scan", v.staff()).getStatusCode().value()).isEqualTo(404);
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    @Test
    void previsit_read_permission_required_403() {
        Visit v = visit();
        ResponseEntity<String> scan = upload(v, CccdQrImages.jpeg(CccdQrImages.payload(), 5));
        Map<String, Object> body = form();
        body.put("idScanId", data(scan).path("scanId").asText());
        submit(v, body);
        String appointmentsOnly = tokenWith("dental_it_appts_only", "dental.appointments.read", "dental.appointments.write");
        String previsitOnly = tokenWith("dental_it_previsit_only", "dental.previsit.read");

        ResponseEntity<String> form = get(previsitPath(v.id()), appointmentsOnly);
        ResponseEntity<String> photo = get(previsitPath(v.id()) + "/id-scan", appointmentsOnly);

        assertThat(form.getStatusCode().value()).isEqualTo(403);
        assertThat(photo.getStatusCode().value()).isEqualTo(403);
        assertThat(views(v.id())).as("a 403 writes no audit row").isZero();
        assertThat(get(previsitPath(v.id()), null).getStatusCode().value()).isEqualTo(401);
        // The day list is still readable with the appointment permission, and flags the form
        assertThat(get(APPOINTMENTS + "?date=" + day(v), appointmentsOnly).getStatusCode().value()).isEqualTo(200);
        assertThat(get(previsitPath(v.id()), previsitOnly).getStatusCode().value()).isEqualTo(200);
        assertThat(get(previsitPath(v.id()) + "/id-scan", previsitOnly).getStatusCode().value()).isEqualTo(200);
        assertThat(views(v.id())).isEqualTo(1);
    }

    private String day(Visit v) {
        return data(get(APPOINTMENTS + "/" + v.id(), v.staff())).path("startsAt").asText().substring(0, 10);
    }

    @Test
    void apply_copies_identity_and_phone() {
        Visit v = visit();
        submit(v, form());
        long version = data(get(previsitPath(v.id()), v.staff())).path("patientVersion").asLong();
        String writeOnly = tokenWith("dental_it_write_and_read", "dental.patients.write", "dental.previsit.read");

        assertThat(apply(v, v.staff(), null).getStatusCode().value()).as("If-Match required").isEqualTo(428);
        assertThat(apply(v, v.staff(), String.valueOf(version + 7)).getStatusCode().value()).as("stale").isEqualTo(409);
        assertThat(apply(v, tokenWith("dental_it_appts_only", "dental.appointments.read", "dental.previsit.read"),
            String.valueOf(version)).getStatusCode().value()).isEqualTo(403);
        assertThat(apply(v, tokenWith("dental_it_patients_write_only", "dental.patients.write"), String.valueOf(version))
            .getStatusCode().value()).as("apply needs previsit.read too").isEqualTo(403);
        ResponseEntity<String> applied = apply(v, writeOnly, String.valueOf(version));

        assertThat(applied.getStatusCode().value()).as(applied.getBody()).isEqualTo(200);
        JsonNode patient = data(applied);
        assertThat(patient.path("fullName").asText()).isEqualTo("Trần Thị Bình");
        assertThat(patient.path("phone").asText()).isEqualTo("+84912345678");
        assertThat(patient.path("version").asLong()).isGreaterThan(version);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dental.patient WHERE id = ?", v.patientId());
        assertThat(row.get("full_name")).isEqualTo("Trần Thị Bình");
        assertThat(row.get("name_search")).as("kept in sync for search").isEqualTo("tran thi binh");
        assertThat(row.get("phone")).isEqualTo("+84912345678");
        assertThat(row.get("date_of_birth").toString()).isEqualTo("1990-05-18");
        assertThat(row.get("id_type")).isEqualTo("CCCD");
        assertThat(row.get("id_number")).isEqualTo("079090123456");
        assertThat(row.get("nationality").toString().trim()).isEqualTo("VN");
        assertThat(row.get("address")).isEqualTo("12 Le Loi, Phu Hoi, Hue");
        assertThat(data(get(DENTAL_API + "/patients?q=tran%20thi%20binh", v.staff())).findValuesAsText("id"))
            .contains(v.patientId().toString());

        List<String> audits = jdbc.queryForList("""
            SELECT after_json::text FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ?
            AND op = 'PATIENT_UPDATE'
            """, String.class, v.patientId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains("fullName", "phone", "dateOfBirth", "idNumber")
            .doesNotContain("079090123456").doesNotContain("Trần").doesNotContain("+84912345678");
        assertThat(jdbc.queryForList("SELECT op FROM change_event WHERE plugin_id = ? AND entity_type = 'Patient' AND entity_id = ? "
            + "ORDER BY version", String.class, DENTAL_PLUGIN_ID, v.patientId())).containsExactly("CREATE", "UPDATE");
        // The old version no longer applies
        assertThat(apply(v, v.staff(), String.valueOf(version)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void apply_refused_without_a_form_or_for_a_closed_appointment() {
        Visit none = visit();
        ResponseEntity<String> noForm = apply(none, none.staff(), "0");
        assertThat(noForm.getStatusCode().value()).isEqualTo(409);

        Visit arrived = visit();
        submit(arrived, form());
        assertThat(post(APPOINTMENTS + "/" + arrived.id() + "/arrive", arrived.staff(), null).getStatusCode().value()).isEqualTo(200);
        long version = data(get(previsitPath(arrived.id()), arrived.staff())).path("patientVersion").asLong();
        assertThat(apply(arrived, arrived.staff(), String.valueOf(version)).getStatusCode().value())
            .as("useful after arrival too").isEqualTo(200);

        Visit noShow = visit();
        submit(noShow, form());
        long noShowVersion = data(get(previsitPath(noShow.id()), noShow.staff())).path("patientVersion").asLong();
        jdbc.update("UPDATE dental.appointment SET status = 'NO_SHOW' WHERE id = ?", noShow.id());
        ResponseEntity<String> closed = apply(noShow, noShow.staff(), String.valueOf(noShowVersion));
        assertThat(closed.getStatusCode().value()).isEqualTo(409);
        assertThat(code(closed)).isEqualTo("DENTAL_INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT full_name FROM dental.patient WHERE id = ?", String.class, noShow.patientId()))
            .startsWith("Old Name");

        assertThat(exchange(HttpMethod.POST, APPOINTMENTS + "/" + UUID.randomUUID() + "/previsit/apply", none.staff(),
            Map.of("formVersion", 0), "If-Match", "0").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void apply_for_a_child_form_without_identity_copies_only_the_phone() {
        String staff = adminToken();
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("fullName", "Child " + tag());
        patient.put("phone", "0933333333");
        patient.put("dateOfBirth", LocalDate.now().minusYears(8).toString());
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("newPatient", patient);
        booking.put("startsAt", futureDay().atTime(9, 0).atOffset(CLINIC));
        JsonNode booked = data(post(APPOINTMENTS, staff, booking));
        UUID id = idOf(booked);
        String token = data(post(linkPath(id), staff, null)).path("url").asText().substring(BASE.length());
        Map<String, Object> childForm = form();
        childForm.remove("identity");
        childForm.put("parentName", "Parent Name");
        childForm.put("parentPhone", "0944444444");
        childForm.put("phone", "0955555555");
        assertThat(exchange(HttpMethod.POST, PUBLIC + "/form", null, childForm, HEADER, token).getStatusCode().value()).isEqualTo(201);

        JsonNode view = data(get(previsitPath(id), staff));
        assertThat(view.path("identity").isNull()).isTrue();
        assertThat(view.path("parentName").asText()).isEqualTo("Parent Name");
        ResponseEntity<String> applied = exchange(HttpMethod.POST, previsitPath(id) + "/apply", staff,
            Map.of("formVersion", view.path("formVersion").asLong()), "If-Match", view.path("patientVersion").asText());

        assertThat(applied.getStatusCode().value()).as(applied.getBody()).isEqualTo(200);
        assertThat(data(applied).path("phone").asText()).isEqualTo("+84955555555");
        assertThat(data(applied).path("fullName").asText()).isEqualTo(booked.path("patientName").asText());
        assertThat(data(applied).path("dateOfBirth").asText()).isEqualTo(patient.get("dateOfBirth"));
    }

    @Test
    void photo_view_is_audited_404_is_not() {
        Visit v = visit();
        Visit noPhoto = visit();
        ResponseEntity<String> scan = upload(v, CccdQrImages.jpeg(CccdQrImages.payload(), 6));
        Map<String, Object> body = form();
        body.put("idScanId", data(scan).path("scanId").asText());
        submit(v, body);
        submit(noPhoto, form());
        String appointmentsOnly = tokenWith("dental_it_appts_only", "dental.appointments.read", "dental.appointments.write");

        assertThat(get(previsitPath(noPhoto.id()) + "/id-scan", noPhoto.staff()).getStatusCode().value()).isEqualTo(404);
        assertThat(get(previsitPath(v.id()) + "/id-scan", appointmentsOnly).getStatusCode().value()).isEqualTo(403);
        assertThat(photoViews(noPhoto.id())).isZero();
        assertThat(photoViews(v.id())).isZero();
        assertThat(get(previsitPath(v.id()) + "/id-scan", v.staff()).getStatusCode().value()).isEqualTo(200);

        assertThat(photoViews(v.id())).isEqualTo(1);
        Map<String, Object> event = jdbc.queryForMap("""
            SELECT user_id, entity_type, coalesce(before_json::text, '') || coalesce(after_json::text, '') AS values
            FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op = 'PREVISIT_PHOTO_VIEW'
            """, v.id());
        assertThat(event.get("user_id")).isNotNull();
        assertThat(event.get("entity_type")).isEqualTo("Appointment");
        assertThat(event.get("values")).isEqualTo("");
    }

    private int photoViews(UUID appointment) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ? AND op = 'PREVISIT_PHOTO_VIEW'",
            Integer.class, appointment);
    }

    @Test
    void photo_read_with_foreign_scan_swapped_is_404() {
        Visit v = visit();
        Visit other = visit();
        submit(v, form());
        UUID foreign = seedScan(other.id(), java.time.Instant.now());
        jdbc.update("UPDATE dental.previsit_form SET id_scan_id = ? WHERE appointment_id = ?", foreign, v.id());

        assertThat(get(previsitPath(v.id()) + "/id-scan", v.staff()).getStatusCode().value()).isEqualTo(404);
        assertThat(photoViews(v.id())).isZero();
    }

    @Test
    void apply_does_not_erase_existing_address() {
        Visit v = visit();
        jdbc.update("UPDATE dental.patient SET address = 'Existing Street 1, Hue' WHERE id = ?", v.patientId());
        Map<String, Object> id = identity();
        id.put("nationality", "FR");
        id.remove("address");
        submit(v, with(form(), "identity", id));
        JsonNode view = data(get(previsitPath(v.id()), v.staff()));

        ResponseEntity<String> applied = apply(v, v.staff(), view.path("patientVersion").asText());

        assertThat(applied.getStatusCode().value()).as(applied.getBody()).isEqualTo(200);
        assertThat(data(applied).path("address").asText()).isEqualTo("Existing Street 1, Hue");
        assertThat(data(applied).path("fullName").asText()).isEqualTo("Trần Thị Bình");
        assertThat(data(applied).path("nationality").asText()).isEqualTo("FR");
        assertThat(data(applied).path("dateOfBirth").asText()).isEqualTo("1990-05-18");
    }

    @Test
    void apply_after_patient_resubmits_is_409_form_changed() {
        Visit v = visit();
        submit(v, form());
        JsonNode view = data(get(previsitPath(v.id()), v.staff()));
        long reviewed = view.path("formVersion").asLong();
        Map<String, Object> changed = form();
        changed.put("phone", "0987654321");
        submit(v, changed);

        ResponseEntity<String> stale = apply(v, v.staff(), view.path("patientVersion").asText(), reviewed);

        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("DENTAL_FORM_CHANGED");
        assertThat(jdbc.queryForObject("SELECT full_name FROM dental.patient WHERE id = ?", String.class, v.patientId()))
            .startsWith("Old Name");
        JsonNode again = data(get(previsitPath(v.id()), v.staff()));
        assertThat(again.path("formVersion").asLong()).isGreaterThan(reviewed);
        assertThat(apply(v, v.staff(), again.path("patientVersion").asText(), again.path("formVersion").asLong())
            .getStatusCode().value()).isEqualTo(200);
        // A body without the version is a 400
        assertThat(exchange(HttpMethod.POST, previsitPath(v.id()) + "/apply", v.staff(), Map.of(), "If-Match", "0")
            .getStatusCode().value()).isEqualTo(400);
    }

    private static Map<String, Object> with(Map<String, Object> form, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(form);
        copy.put(key, value);
        return copy;
    }

    @Test
    void day_list_shows_submitted() {
        LocalDate day = futureDay();
        Visit first = visitOn(day, 9);
        Visit second = visitOn(day, 10);
        Visit third = visitOn(day, 11);
        submit(first, form());
        submit(third, form());

        JsonNode list = data(get(APPOINTMENTS + "?date=" + day, first.staff()));

        Map<String, String> flags = new LinkedHashMap<>();
        list.forEach(item -> flags.put(item.path("id").asText(), item.path("previsit").asText()));
        assertThat(flags).containsEntry(first.id().toString(), "SUBMITTED")
            .containsEntry(second.id().toString(), "NONE").containsEntry(third.id().toString(), "SUBMITTED");
        assertThat(data(get(APPOINTMENTS + "/" + first.id(), first.staff())).path("previsit").asText()).isEqualTo("SUBMITTED");
        assertThat(data(get(APPOINTMENTS + "/" + second.id(), second.staff())).path("previsit").asText()).isEqualTo("NONE");
        assertThat(list.toString()).doesNotContain("SECRETMED").doesNotContain("079090123456");

        // A status change keeps the flag; cancelling clears it together with the form
        JsonNode arrived = data(post(APPOINTMENTS + "/" + first.id() + "/arrive", first.staff(), null));
        assertThat(arrived.path("previsit").asText()).isEqualTo("SUBMITTED");
        JsonNode cancelled = data(post(APPOINTMENTS + "/" + third.id() + "/cancel", third.staff(), null));
        assertThat(cancelled.path("previsit").asText()).isEqualTo("NONE");
        List<String> after = new ArrayList<>();
        data(get(APPOINTMENTS + "?date=" + day, first.staff())).forEach(item -> after.add(item.path("previsit").asText()));
        assertThat(after).containsExactly("SUBMITTED", "NONE", "NONE");
    }
}
