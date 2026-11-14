package com.crm.foundation.dental;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Patients and phone bookings over HTTP: search, booking, the clinic-local day list, transitions, audit, sync. */
class PatientAppointmentIT extends DentalITSupport {

    private static final ZoneOffset CLINIC = ZoneOffset.ofHours(7);
    private static final String PATIENTS = DENTAL_API + "/patients";
    private static final String APPOINTMENTS = DENTAL_API + "/appointments";

    @DynamicPropertySource
    static void pluginDirectory(DynamicPropertyRegistry registry) {
        registry.add("foundation.plugins.directory", () -> dentalPluginDirectory().toString());
    }

    @Test
    void book_new_patient_and_list_day() {
        String token = adminToken();
        String tag = tag();
        LocalDate day = farDay();
        ResponseEntity<String> late = book(token, newPatient("Late " + tag, phone(), "1990-05-17"), at(day, 23, 30), 30);
        ResponseEntity<String> booked = post(APPOINTMENTS, token, bookBody(null,
            newPatient("Nguyễn Văn " + tag, "0912 345 678", "1990-05-17"), at(day, 9, 0), 45, "Dr An", "Đau răng"));
        ResponseEntity<String> early = book(token, newPatient("Early " + tag, phone(), "1990-05-17"), at(day, 8, 0), 30);
        // 00:30 clinic time the next day is still the previous day in UTC: it must not appear in this day's list
        book(token, newPatient("Next " + tag, phone(), "1990-05-17"), at(day.plusDays(1), 0, 30), 30);

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        JsonNode view = data(booked);
        assertThat(view.path("status").asText()).isEqualTo("BOOKED");
        assertThat(view.path("previsit").asText()).isEqualTo("NONE");
        assertThat(view.path("durationMinutes").asInt()).isEqualTo(45);
        assertThat(view.path("dentist").asText()).isEqualTo("Dr An");
        assertThat(view.path("patientName").asText()).isEqualTo("Nguyễn Văn " + tag);
        assertThat(OffsetDateTime.parse(view.path("startsAt").asText()).toInstant())
            .isEqualTo(at(day, 9, 0).toInstant());
        assertThat(jdbc.queryForObject("SELECT phone FROM dental.patient WHERE id = ?", String.class,
            UUID.fromString(view.path("patientId").asText()))).isEqualTo("+84912345678");

        ResponseEntity<String> list = get(APPOINTMENTS + "?date=" + day, token);
        assertThat(list.getStatusCode().value()).as(list.getBody()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        data(list).forEach(a -> {
            if (a.path("patientName").asText().endsWith(tag)) {
                names.add(a.path("patientName").asText());
            }
        });
        assertThat(names).as("ordered by start, next local day excluded")
            .containsExactly("Early " + tag, "Nguyễn Văn " + tag, "Late " + tag);
        assertThat(early.getStatusCode().value()).isEqualTo(201);
        assertThat(late.getStatusCode().value()).isEqualTo(201);
        assertThat(get(APPOINTMENTS, token).getStatusCode().value()).as("date is required").isEqualTo(400);
        assertThat(get(APPOINTMENTS + "?date=14-11-2026", token).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void book_existing_patient() {
        String token = adminToken();
        String phone = phone();
        UUID patientId = createPatient(token, "Existing " + tag(), phone, "1985-01-02");
        LocalDate day = farDay();

        ResponseEntity<String> booked = post(APPOINTMENTS, token, bookBody(patientId, null, at(day, 10, 0), null, null, null));

        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        assertThat(data(booked).path("patientId").asText()).isEqualTo(patientId.toString());
        assertThat(data(booked).path("durationMinutes").asInt()).as("default duration").isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dental.patient WHERE phone = ?", Integer.class,
            normalized(phone))).isEqualTo(1);

        assertThat(post(APPOINTMENTS, token, bookBody(UUID.randomUUID(), null, at(day, 11, 0), null, null, null))
            .getStatusCode().value()).isEqualTo(404);
        Map<String, Object> both = bookBody(patientId, newPatient("Both", phone(), "1990-01-01"), at(day, 11, 0), null, null, null);
        assertThat(post(APPOINTMENTS, token, both).getStatusCode().value()).isEqualTo(400);
        assertThat(post(APPOINTMENTS, token, bookBody(null, null, at(day, 11, 0), null, null, null))
            .getStatusCode().value()).isEqualTo(400);
        Map<String, Object> noTime = bookBody(patientId, null, null, null, null, null);
        assertThat(post(APPOINTMENTS, token, noTime).getStatusCode().value()).isEqualTo(400);
        assertThat(post(APPOINTMENTS, token, bookBody(patientId, null, at(day, 11, 0), 0, null, null))
            .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void shared_phone_search_returns_all() {
        String token = adminToken();
        String tag = tag();
        String phone = phone();
        createPatient(token, "Parent " + tag, phone, "1980-03-04");
        createPatient(token, "Child " + tag, phone, "2020-06-07");
        createPatient(token, "Other " + tag, phone(), "1970-01-01");

        ResponseEntity<String> byPhone = get(PATIENTS + "?phone=" + phone, token);
        List<String> names = names(byPhone);

        assertThat(names).containsExactly("Child " + tag, "Parent " + tag);
        assertThat(names(get(PATIENTS + "?phone=" + normalized(phone).replace("+", "%2B"), token)))
            .as("international form matches the same rows").containsExactly("Child " + tag, "Parent " + tag);
        assertThat(names(get(PATIENTS + "?q=" + ("pARent " + tag).replace(" ", "%20"), token)))
            .containsExactly("Parent " + tag);
        assertThat(names(get(PATIENTS + "?phone=" + phone + "&q=child", token))).containsExactly("Child " + tag);

        ResponseEntity<String> bad = get(PATIENTS + "?phone=0212345678", token);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(json(bad).path("code").asText()).isEqualTo("DENTAL_INVALID_PHONE");
        assertThat(get(PATIENTS, token).getStatusCode().value()).as("phone or q is required").isEqualTo(400);
    }

    @Test
    void search_is_capped_at_50() {
        String token = adminToken();
        String tag = tag();
        for (int i = 0; i < 52; i++) {
            createPatient(token, String.format("Cap %s %02d", tag, i), phone(), "1990-01-01");
        }

        List<String> found = names(get(PATIENTS + "?q=cap%20" + tag, token));

        assertThat(found).hasSize(50);
        assertThat(found).isSorted();
    }

    @Test
    void dob_required_400() {
        String token = adminToken();
        String tag = tag();

        Map<String, Object> noDob = newPatient("NoDob " + tag, phone(), null);
        assertThat(post(PATIENTS, token, noDob).getStatusCode().value()).isEqualTo(400);
        assertThat(post(APPOINTMENTS, token, bookBody(null, noDob, at(farDay(), 9, 0), null, null, null))
            .getStatusCode().value()).isEqualTo(400);
        ResponseEntity<String> future = post(PATIENTS, token,
            newPatient("Future " + tag, phone(), LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1).toString()));
        assertThat(future.getStatusCode().value()).as(future.getBody()).isEqualTo(400);
        assertThat(json(future).path("code").asText()).isEqualTo("DENTAL_INVALID_PATIENT");
        assertThat(post(PATIENTS, token, newPatient("BadPhone " + tag, "12345", "1990-01-01")).getStatusCode().value())
            .isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dental.patient WHERE full_name LIKE ?", Integer.class,
            "%" + tag)).as("nothing was stored").isZero();
    }

    @Test
    void update_patient_needs_if_match() {
        String token = adminToken();
        UUID id = createPatient(token, "Before " + tag(), phone(), "1990-01-01");
        long version = version(id, "patient");
        Map<String, Object> body = newPatient("After " + tag(), "0987654321", "1991-02-03");

        assertThat(put(PATIENTS + "/" + id, token, body, null).getStatusCode().value()).isEqualTo(428);
        assertThat(put(PATIENTS + "/" + id, token, body, String.valueOf(version + 5)).getStatusCode().value())
            .isEqualTo(409);
        ResponseEntity<String> updated = put(PATIENTS + "/" + id, token, body, "\"" + version + "\"");
        assertThat(updated.getStatusCode().value()).as(updated.getBody()).isEqualTo(200);
        assertThat(data(updated).path("phone").asText()).isEqualTo("+84987654321");
        assertThat(data(updated).path("version").asLong()).isEqualTo(version + 1);
        assertThat(put(PATIENTS + "/" + UUID.randomUUID(), token, body, "0").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void reschedule_changes_time() {
        String token = adminToken();
        LocalDate day = farDay();
        JsonNode booked = data(book(token, newPatient("Move " + tag(), phone(), "1990-01-01"), at(day, 9, 0), 30));
        UUID id = UUID.fromString(booked.path("id").asText());
        long version = booked.path("version").asLong();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("startsAt", at(day.plusDays(1), 14, 0));
        change.put("durationMinutes", 60);
        change.put("dentist", "Dr Binh");

        assertThat(patch(APPOINTMENTS + "/" + id, token, change, null).getStatusCode().value()).isEqualTo(428);
        assertThat(patch(APPOINTMENTS + "/" + id, token, change, String.valueOf(version + 3)).getStatusCode().value())
            .as("stale version").isEqualTo(409);
        ResponseEntity<String> moved = patch(APPOINTMENTS + "/" + id, token, change, String.valueOf(version));

        assertThat(moved.getStatusCode().value()).as(moved.getBody()).isEqualTo(200);
        JsonNode view = data(moved);
        assertThat(OffsetDateTime.parse(view.path("startsAt").asText()).toInstant())
            .isEqualTo(at(day.plusDays(1), 14, 0).toInstant());
        assertThat(view.path("durationMinutes").asInt()).isEqualTo(60);
        assertThat(view.path("dentist").asText()).isEqualTo("Dr Binh");
        assertThat(view.path("status").asText()).isEqualTo("BOOKED");
        assertThat(view.path("version").asLong()).isEqualTo(version + 1);
        assertThat(ids(get(APPOINTMENTS + "?date=" + day, token))).doesNotContain(id.toString());
        assertThat(ids(get(APPOINTMENTS + "?date=" + day.plusDays(1), token))).contains(id.toString());
    }

    @Test
    void cancel_arrive_noshow_transitions_and_409s() {
        String token = adminToken();
        LocalDate past = LocalDate.now().minusDays(3);

        UUID cancelled = id(book(token, newPatient("Cancel " + tag(), phone(), "1990-01-01"), at(farDay(), 9, 0), 30));
        assertThat(status(post(APPOINTMENTS + "/" + cancelled + "/cancel", token, null))).isEqualTo("CANCELLED");
        assertConflict(post(APPOINTMENTS + "/" + cancelled + "/cancel", token, null));
        assertConflict(post(APPOINTMENTS + "/" + cancelled + "/arrive", token, null));
        assertConflict(post(APPOINTMENTS + "/" + cancelled + "/no-show", token, null));
        assertConflict(patch(APPOINTMENTS + "/" + cancelled, token, Map.of("startsAt", at(farDay(), 9, 0)),
            String.valueOf(version(cancelled))));

        UUID arrived = id(book(token, newPatient("Arrive " + tag(), phone(), "1990-01-01"), at(past, 9, 0), 30));
        assertThat(status(post(APPOINTMENTS + "/" + arrived + "/arrive", token, null))).isEqualTo("ARRIVED");
        assertConflict(post(APPOINTMENTS + "/" + arrived + "/arrive", token, null));
        assertConflict(post(APPOINTMENTS + "/" + arrived + "/cancel", token, null));
        assertConflict(post(APPOINTMENTS + "/" + arrived + "/no-show", token, null));

        UUID noShow = id(book(token, newPatient("NoShow " + tag(), phone(), "1990-01-01"), at(past, 10, 0), 30));
        assertThat(status(post(APPOINTMENTS + "/" + noShow + "/no-show", token, null))).isEqualTo("NO_SHOW");
        assertConflict(post(APPOINTMENTS + "/" + noShow + "/no-show", token, null));
        assertConflict(post(APPOINTMENTS + "/" + noShow + "/arrive", token, null));
        assertConflict(post(APPOINTMENTS + "/" + noShow + "/cancel", token, null));

        assertThat(post(APPOINTMENTS + "/" + UUID.randomUUID() + "/cancel", token, null).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void no_show_before_start_409() {
        String token = adminToken();
        UUID id = id(book(token, newPatient("Early " + tag(), phone(), "1990-01-01"), at(farDay(), 9, 0), 30));

        ResponseEntity<String> response = post(APPOINTMENTS + "/" + id + "/no-show", token, null);

        assertConflict(response);
        assertThat(jdbc.queryForObject("SELECT status FROM dental.appointment WHERE id = ?", String.class, id))
            .isEqualTo("BOOKED");
    }

    @Test
    void name_search_ignores_accents_and_case() {
        String token = adminToken();
        String tag = tag();
        createPatient(token, "Nguyễn Văn An " + tag, phone(), "1990-01-01");
        createPatient(token, "Trần Thị Bình " + tag, phone(), "1990-01-01");

        assertThat(names(get(PATIENTS + "?q=nguyen%20van%20an%20" + tag, token))).containsExactly("Nguyễn Văn An " + tag);
        assertThat(names(get(PATIENTS + "?q=NGUY%E1%BB%84N%20VĂN%20AN%20" + tag.toUpperCase(), token)))
            .containsExactly("Nguyễn Văn An " + tag);
        assertThat(names(get(PATIENTS + "?q=an%20" + tag, token))).containsExactly("Nguyễn Văn An " + tag);
        assertThat(names(get(PATIENTS + "?q=" + "binh%20" + tag, token))).containsExactly("Trần Thị Bình " + tag);
    }

    @Test
    void reschedule_arrived_is_409() {
        String token = adminToken();
        JsonNode booked = data(book(token, newPatient("Arrived " + tag(), phone(), "1990-01-01"),
            at(LocalDate.now().minusDays(1), 9, 0), 30));
        UUID id = UUID.fromString(booked.path("id").asText());
        assertThat(status(post(APPOINTMENTS + "/" + id + "/arrive", token, null))).isEqualTo("ARRIVED");

        ResponseEntity<String> response = patch(APPOINTMENTS + "/" + id, token,
            Map.of("startsAt", at(farDay(), 9, 0)), String.valueOf(version(id)));

        assertConflict(response);
        assertThat(json(response).path("code").asText()).isEqualTo("DENTAL_INVALID_STATE");
    }

    @Test
    void get_appointment_requires_appointments_read_403() {
        String admin = adminToken();
        UUID id = id(book(admin, newPatient("Get " + tag(), phone(), "1990-01-01"), at(farDay(), 9, 0), 30));

        assertThat(get(APPOINTMENTS + "/" + id, tokenWith("dental_it_get_nothing", "dental.patients.read"))
            .getStatusCode().value()).isEqualTo(403);
        assertThat(get(APPOINTMENTS + "/" + id, tokenWith("dental_it_appointments_read", "dental.appointments.read"))
            .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void parallel_cancel_and_arrive_one_wins() throws Exception {
        String token = adminToken();
        UUID id = id(book(token, newPatient("Race " + tag(), phone(), "1990-01-01"), at(farDay(), 9, 0), 30));
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        ResponseEntity<String> cancel;
        ResponseEntity<String> arrive;
        try {
            java.util.concurrent.Future<ResponseEntity<String>> c = pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return post(APPOINTMENTS + "/" + id + "/cancel", token, null);
            });
            java.util.concurrent.Future<ResponseEntity<String>> a = pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return post(APPOINTMENTS + "/" + id + "/arrive", token, null);
            });
            cancel = c.get(60, java.util.concurrent.TimeUnit.SECONDS);
            arrive = a.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Integer> codes = List.of(cancel.getStatusCode().value(), arrive.getStatusCode().value());
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        String winner = cancel.getStatusCode().value() == 200 ? "CANCELLED" : "ARRIVED";
        assertThat(jdbc.queryForObject("SELECT status FROM dental.appointment WHERE id = ?", String.class, id))
            .isEqualTo(winner);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id = ?
              AND op IN ('APPOINTMENT_CANCEL', 'APPOINTMENT_ARRIVE', 'APPOINTMENT_NO_SHOW', 'APPOINTMENT_RESCHEDULE')
            """, Integer.class, id)).isEqualTo(1);
    }

    @Test
    void permissions_403() {
        String admin = adminToken();
        UUID appointment = id(book(admin, newPatient("Perm " + tag(), phone(), "1990-01-01"), at(farDay(), 9, 0), 30));
        String patientsRead = tokenWith("dental_it_patients_read", "dental.patients.read");
        String appointmentsRead = tokenWith("dental_it_appointments_read", "dental.appointments.read");
        String nothing = tokenWith("dental_it_nothing");
        LocalDate day = farDay();

        assertThat(get(PATIENTS + "?q=perm", patientsRead).getStatusCode().value()).isEqualTo(200);
        assertThat(post(PATIENTS, patientsRead, newPatient("X", phone(), "1990-01-01")).getStatusCode().value())
            .isEqualTo(403);
        assertThat(put(PATIENTS + "/" + UUID.randomUUID(), patientsRead, newPatient("X", phone(), "1990-01-01"), "0")
            .getStatusCode().value()).isEqualTo(403);
        assertThat(get(APPOINTMENTS + "?date=" + day, patientsRead).getStatusCode().value()).isEqualTo(403);

        assertThat(get(APPOINTMENTS + "?date=" + day, appointmentsRead).getStatusCode().value()).isEqualTo(200);
        assertThat(get(PATIENTS + "?q=perm", appointmentsRead).getStatusCode().value()).isEqualTo(403);
        assertThat(post(APPOINTMENTS, appointmentsRead, bookBody(null, newPatient("X", phone(), "1990-01-01"),
            at(day, 9, 0), null, null, null)).getStatusCode().value()).isEqualTo(403);
        assertThat(patch(APPOINTMENTS + "/" + appointment, appointmentsRead, Map.of("startsAt", at(day, 9, 0)), "0")
            .getStatusCode().value()).isEqualTo(403);
        for (String action : List.of("cancel", "arrive", "no-show")) {
            assertThat(post(APPOINTMENTS + "/" + appointment + "/" + action, appointmentsRead, null)
                .getStatusCode().value()).as(action).isEqualTo(403);
        }

        assertThat(get(PATIENTS + "?q=perm", nothing).getStatusCode().value()).isEqualTo(403);
        assertThat(get(APPOINTMENTS + "?date=" + day, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void audit_has_no_identity_values() {
        String token = adminToken();
        String tag = tag();
        String name = "Bí Mật " + tag;
        String phone = phone();
        String idNumber = "0790" + (10_000_000 + (long) (Math.random() * 89_000_000));
        String address = "99 Secret Street " + tag;
        String reason = "Đau nhức " + tag;
        Map<String, Object> patient = newPatient(name, phone, "1977-08-09");
        patient.put("idType", "CCCD");
        patient.put("idNumber", idNumber);
        patient.put("nationality", "VN");
        patient.put("address", address);
        LocalDate day = farDay();

        ResponseEntity<String> booked = post(APPOINTMENTS, token,
            bookBody(null, patient, at(day, 9, 0), 30, "Dr An", reason));
        assertThat(booked.getStatusCode().value()).as(booked.getBody()).isEqualTo(201);
        UUID appointmentId = UUID.fromString(data(booked).path("id").asText());
        UUID patientId = UUID.fromString(data(booked).path("patientId").asText());
        assertThat(patch(APPOINTMENTS + "/" + appointmentId, token, Map.of("startsAt", at(day, 11, 0)),
            String.valueOf(data(booked).path("version").asLong())).getStatusCode().value()).isEqualTo(200);
        assertThat(put(PATIENTS + "/" + patientId, token, patient, String.valueOf(version(patientId, "patient")))
            .getStatusCode().value()).isEqualTo(200);
        assertThat(post(APPOINTMENTS + "/" + appointmentId + "/arrive", token, null).getStatusCode().value())
            .isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT entity_type, op, coalesce(before_json::text, '') AS before, coalesce(after_json::text, '') AS after
            FROM audit_event WHERE plugin_id = 'com.crm.dental' AND entity_id IN (?, ?)
            """, appointmentId, patientId);
        assertThat(rows.stream().map(r -> r.get("op").toString()).toList()).containsExactlyInAnyOrder(
            "PATIENT_CREATE", "PATIENT_UPDATE", "APPOINTMENT_BOOK", "APPOINTMENT_RESCHEDULE", "APPOINTMENT_ARRIVE");
        String everything = rows.stream().map(r -> r.get("before") + " " + r.get("after")).reduce("", String::concat);
        assertThat(everything)
            .doesNotContain(name).doesNotContain(tag).doesNotContain(phone).doesNotContain(normalized(phone))
            .doesNotContain(phone.substring(1)).doesNotContain(idNumber).doesNotContain("1977-08-09")
            .doesNotContain("Secret Street").doesNotContain("Đau");
        Map<String, Object> update = rows.stream().filter(r -> r.get("op").equals("PATIENT_UPDATE")).findFirst().orElseThrow();
        assertThat(parse(update.get("after").toString()).path("changedFields").isArray()).isTrue();
    }

    @Test
    void change_events_written() {
        String token = adminToken();
        JsonNode booked = data(book(token, newPatient("Sync " + tag(), phone(), "1990-01-01"), at(farDay(), 9, 0), 30));
        UUID appointmentId = UUID.fromString(booked.path("id").asText());
        UUID patientId = UUID.fromString(booked.path("patientId").asText());
        post(APPOINTMENTS + "/" + appointmentId + "/cancel", token, null);

        assertThat(jdbc.queryForList("SELECT op FROM change_event WHERE plugin_id = ? AND entity_type = 'Patient' "
            + "AND entity_id = ? ORDER BY version", String.class, DENTAL_PLUGIN_ID, patientId)).containsExactly("CREATE");
        assertThat(jdbc.queryForList("SELECT op FROM change_event WHERE plugin_id = ? AND entity_type = 'Appointment' "
            + "AND entity_id = ? ORDER BY version", String.class, DENTAL_PLUGIN_ID, appointmentId))
            .containsExactly("CREATE", "UPDATE");
    }

    // ---- helpers ----

    private ResponseEntity<String> book(String token, Map<String, Object> newPatient, OffsetDateTime startsAt, int minutes) {
        ResponseEntity<String> response = post(APPOINTMENTS, token, bookBody(null, newPatient, startsAt, minutes, null, null));
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return response;
    }

    private static Map<String, Object> bookBody(UUID patientId, Map<String, Object> newPatient, OffsetDateTime startsAt,
            Integer minutes, String dentist, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patientId);
        body.put("newPatient", newPatient);
        body.put("startsAt", startsAt);
        body.put("durationMinutes", minutes);
        body.put("dentist", dentist);
        body.put("reason", reason);
        return body;
    }

    private static Map<String, Object> newPatient(String name, String phone, String dob) {
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("fullName", name);
        patient.put("phone", phone);
        patient.put("dateOfBirth", dob);
        return patient;
    }

    private UUID createPatient(String token, String name, String phone, String dob) {
        ResponseEntity<String> created = post(PATIENTS, token, newPatient(name, phone, dob));
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    private List<String> names(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        data(response).forEach(p -> names.add(p.path("fullName").asText()));
        return names;
    }

    private List<String> ids(ResponseEntity<String> response) {
        List<String> ids = new ArrayList<>();
        data(response).forEach(a -> ids.add(a.path("id").asText()));
        return ids;
    }

    private UUID id(ResponseEntity<String> response) {
        return UUID.fromString(data(response).path("id").asText());
    }

    private String status(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return data(response).path("status").asText();
    }

    private void assertConflict(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
    }

    private long version(UUID appointmentId) {
        return version(appointmentId, "appointment");
    }

    private long version(UUID id, String table) {
        return jdbc.queryForObject("SELECT version FROM dental." + table + " WHERE id = ?", Long.class, id);
    }

    /** A clinic-local time on {@code day}. */
    private static OffsetDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atOffset(CLINIC);
    }

    /** A day far in the future, random so tests sharing the database don't meet in one day list. */
    private static LocalDate farDay() {
        return LocalDate.now().plusDays(400 + (long) (Math.random() * 20_000));
    }

    private JsonNode parse(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String normalized(String local) {
        return "+84" + local.substring(1);
    }

    private static String phone() {
        return "09" + (10_000_000 + (long) (Math.random() * 89_000_000));
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
