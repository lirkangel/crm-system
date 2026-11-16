package com.crm.foundation.dental;

import com.crm.foundation.Plugin.PluginContexts;
import com.crm.foundation.Plugin.PluginHost;
import com.crm.foundation.Plugin.PluginProblem;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Helpers of the pre-visit link ITs: bookings over HTTP, seeded rows, and the plugin's own beans by reflection. */
abstract class VisitLinkITSupport extends DentalITSupport {

    protected static final ZoneOffset CLINIC = ZoneOffset.ofHours(7);
    protected static final String APPOINTMENTS = DENTAL_API + "/appointments";

    @Autowired
    private PluginHost pluginHost;

    protected static String linkPath(UUID appointmentId) {
        return APPOINTMENTS + "/" + appointmentId + "/visit-link";
    }

    /** Books a new patient (adult) at {@code day} 10:00 clinic time; returns the appointment view. */
    protected JsonNode book(String token, LocalDate day) {
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("fullName", "Link " + tag());
        patient.put("phone", "09" + (10_000_000 + ThreadLocalRandom.current().nextLong(89_000_000)));
        patient.put("dateOfBirth", "1990-05-17");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newPatient", patient);
        body.put("startsAt", day.atTime(10, 0).atOffset(CLINIC));
        ResponseEntity<String> response = post(APPOINTMENTS, token, body);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return data(response);
    }

    protected static UUID idOf(JsonNode view) {
        return UUID.fromString(view.path("id").asText());
    }

    /** One counter per JVM: every call gets its own day, so appointments of different tests never share a day. */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_DAY = new java.util.concurrent.atomic.AtomicLong(3);

    protected static LocalDate futureDay() {
        return LocalDate.now().plusDays(NEXT_DAY.getAndIncrement());
    }

    protected static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A bean of the running dental plugin context. */
    protected Object bean(String name) {
        return PluginContexts.running(pluginHost, DENTAL_PLUGIN_ID).getBean(name);
    }

    /** What {@code VisitLinkService.resolve(token)} answers: 200, or the status of the problem it throws. */
    protected int resolveStatus(String token) {
        Object service = bean("visitLinkService");
        try {
            service.getClass().getMethod("resolve", String.class).invoke(service, token);
            return 200;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof PluginProblem problem) {
                return problem.status();
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    protected int purgeScansOlderThan(Instant cutoff) {
        Object service = bean("dentalIdScanService");
        try {
            return (int) service.getClass().getMethod("purgeOlderThan", Instant.class).invoke(service, cutoff);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    protected UUID seedScan(UUID appointment, Instant uploadedAt) {
        UUID id = UUID.randomUUID();
        byte[] image = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        byte[] sha = new byte[32];
        ThreadLocalRandom.current().nextBytes(sha);
        jdbc.update("""
            INSERT INTO dental.id_scan (id, appointment_id, content_type, image, sha256, size_bytes, uploaded_at, version)
            VALUES (?, ?, 'image/jpeg', ?, ?, ?, ?, 0)
            """, id, appointment, image, sha, image.length, Timestamp.from(uploadedAt));
        return id;
    }

    protected void seedLink(UUID appointment, Instant expiresAt) {
        byte[] hash = new byte[32];
        ThreadLocalRandom.current().nextBytes(hash);
        jdbc.update("""
            INSERT INTO dental.visit_link (appointment_id, token_hash, created_at, expires_at)
            VALUES (?, ?, now(), ?)
            """, appointment, hash, Timestamp.from(expiresAt));
    }

    /** A submitted form of the appointment holding {@code scanId} (may be null). */
    protected UUID seedForm(UUID appointment, UUID scanId) {
        UUID patient = jdbc.queryForObject("SELECT patient_id FROM dental.appointment WHERE id = ?", UUID.class, appointment);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO dental.previsit_form (id, appointment_id, patient_id, id_scan_id, consent_at, notice_version, submitted_at)
            VALUES (?, ?, ?, ?, now(), '2026-11-14', now())
            """, id, appointment, patient, scanId);
        return id;
    }

    protected int count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM dental." + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    protected static OffsetDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atOffset(CLINIC);
    }
}
