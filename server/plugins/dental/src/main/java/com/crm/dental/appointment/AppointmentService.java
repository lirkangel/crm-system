package com.crm.dental.appointment;

import com.crm.dental.DentalConfig;
import com.crm.dental.DentalErrors;
import com.crm.dental.IfMatch;
import com.crm.dental.link.VisitLinkRepository;
import com.crm.dental.link.VisitLinkService;
import com.crm.dental.patient.Patient;
import com.crm.dental.patient.PatientService;
import com.crm.dental.patient.PatientRepository;
import com.crm.dental.previsit.PrevisitFormRepository;
import com.crm.dental.scan.DentalIdScanRepository;
import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phone bookings: book, list a clinic day, reschedule and the status changes. Only a {@code BOOKED} appointment
 * changes; the row is locked first so of two concurrent changes the second sees the first's result. The audit gets
 * ids, status, time and duration; no name, phone or reason.
 */
@Service
@RequiredArgsConstructor
public class AppointmentService {

    static final String ENTITY_TYPE = "Appointment";
    static final String AUDIT_BOOK = "APPOINTMENT_BOOK";
    static final String AUDIT_RESCHEDULE = "APPOINTMENT_RESCHEDULE";
    static final String AUDIT_CANCEL = "APPOINTMENT_CANCEL";
    static final String AUDIT_ARRIVE = "APPOINTMENT_ARRIVE";
    static final String AUDIT_NO_SHOW = "APPOINTMENT_NO_SHOW";

    static final int DEFAULT_MINUTES = 30;
    static final int MAX_MINUTES = 480;

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock dentalClock;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final VisitLinkRepository visitLinks;
    private final DentalIdScanRepository idScans;
    private final PrevisitFormRepository forms;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    /**
     * Books one appointment; an inline new patient is created only if the booking succeeds.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_INVALID_APPOINTMENT} (400) unless exactly one
     *         of patientId/newPatient is given or for a bad duration; {@code DENTAL_NOT_FOUND} (404) for an
     *         unknown patient; patient problems from {@link PatientService}
     */
    @Transactional
    public AppointmentView book(BookRequest request) {
        if ((request.patientId() == null) == (request.newPatient() == null)) {
            throw DentalErrors.invalidAppointment("Give exactly one of patientId (an existing patient) or newPatient");
        }
        int minutes = minutes(request.durationMinutes(), DEFAULT_MINUTES);
        Patient patient = request.patientId() == null ? null : patients.findById(request.patientId())
            .orElseThrow(() -> DentalErrors.notFound("Patient not found"));
        if (patient == null) {
            patient = patientService.createEntity(request.newPatient());
        }

        Appointment appointment = new Appointment();
        appointment.setPatientId(patient.getId());
        appointment.setStartsAt(instant(request.startsAt()));
        appointment.setDurationMinutes(minutes);
        appointment.setDentist(blankToNull(request.dentist()));
        appointment.setReason(blankToNull(request.reason()));
        appointment.setStatus(AppointmentStatus.BOOKED);
        appointment.setCreatedBy(currentUser.id().orElse(null));
        appointment.setCreatedAt(now());
        Appointment saved = appointments.saveAndFlush(appointment);

        changes.record(ENTITY_TYPE, saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        audit.record(ENTITY_TYPE, saved.getId(), AUDIT_BOOK, null, json(auditState(saved)));
        return AppointmentView.of(saved, patient.getFullName(), patient.getPhone());
    }

    @Transactional(readOnly = true)
    public AppointmentView get(UUID id) {
        return appointments.findDetailed(id).stream().findFirst().map(AppointmentView::of)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
    }

    /** Appointments of any status starting on the clinic-local {@code date}, by start time. */
    @Transactional(readOnly = true)
    public List<AppointmentView> listDay(LocalDate date) {
        Instant from = date.atStartOfDay(DentalConfig.CLINIC_ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(DentalConfig.CLINIC_ZONE).toInstant();
        return appointments.findDetailedBetween(from, to).stream().map(AppointmentView::of).toList();
    }

    /**
     * Moves a {@code BOOKED} appointment. Pre-visit data, once it exists, stays with the appointment.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404),
     *         {@code DENTAL_VERSION_REQUIRED} (428), {@code DENTAL_INVALID_STATE} (409) unless booked; a stale
     *         {@code If-Match} is a 409
     */
    @Transactional
    public AppointmentView reschedule(UUID id, RescheduleRequest request, String ifMatch) {
        Appointment appointment = appointments.lockById(id)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
        IfMatch.requireVersion(ifMatch, appointment.getVersion(), Appointment.class, id);
        requireBooked(appointment, "rescheduled");
        String before = json(auditState(appointment));

        appointment.setStartsAt(instant(request.startsAt()));
        appointment.setDurationMinutes(minutes(request.durationMinutes(), appointment.getDurationMinutes()));
        if (request.dentist() != null) {
            appointment.setDentist(blankToNull(request.dentist()));
        }
        // The visit link stays and follows the appointment to its new day
        visitLinks.updateExpiry(id, VisitLinkService.expiryFor(appointment.getStartsAt()));
        return saveChange(appointment, AUDIT_RESCHEDULE, before);
    }

    @Transactional
    public AppointmentView cancel(UUID id) {
        return transition(id, AppointmentStatus.CANCELLED, AUDIT_CANCEL, "cancelled");
    }

    @Transactional
    public AppointmentView arrive(UUID id) {
        return transition(id, AppointmentStatus.ARRIVED, AUDIT_ARRIVE, "marked arrived");
    }

    /** Only once the start time has passed. */
    @Transactional
    public AppointmentView noShow(UUID id) {
        return transition(id, AppointmentStatus.NO_SHOW, AUDIT_NO_SHOW, "marked no-show");
    }

    private AppointmentView transition(UUID id, AppointmentStatus target, String auditOp, String verb) {
        Appointment appointment = appointments.lockById(id)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
        requireBooked(appointment, verb);
        if (target == AppointmentStatus.NO_SHOW && Instant.now(dentalClock).isBefore(appointment.getStartsAt())) {
            throw DentalErrors.invalidState("An appointment can be marked no-show only after its start time");
        }
        String before = json(auditState(appointment));
        appointment.setStatus(target);
        if (target == AppointmentStatus.CANCELLED) {
            // Retention: a cancelled appointment's form and photos go; the link stays so the public page says "closed"
            forms.deleteByAppointment(id);
            idScans.deleteByAppointment(id);
        }
        return saveChange(appointment, auditOp, before);
    }

    private AppointmentView saveChange(Appointment appointment, String auditOp, String before) {
        Appointment saved = appointments.saveAndFlush(appointment);
        changes.record(ENTITY_TYPE, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        audit.record(ENTITY_TYPE, saved.getId(), auditOp, before, json(auditState(saved)));
        Patient patient = patients.findById(saved.getPatientId()).orElseThrow();
        return AppointmentView.of(saved, patient.getFullName(), patient.getPhone());
    }

    private static void requireBooked(Appointment appointment, String verb) {
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw DentalErrors.invalidState("Only a booked appointment can be " + verb + "; this one is "
                + appointment.getStatus());
        }
    }

    private static int minutes(Integer requested, int fallback) {
        int minutes = requested == null ? fallback : requested;
        if (minutes < 5 || minutes > MAX_MINUTES) {
            throw DentalErrors.invalidAppointment("durationMinutes must be between 5 and " + MAX_MINUTES);
        }
        return minutes;
    }

    /** Microsecond precision, as stored by {@code timestamptz}, so responses and later reads agree. */
    private static Instant instant(OffsetDateTime time) {
        return time.toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private Instant now() {
        return Instant.now(dentalClock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** What the audit keeps of an appointment: ids and scheduling facts only. */
    private static Map<String, Object> auditState(Appointment appointment) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", appointment.getId());
        state.put("patientId", appointment.getPatientId());
        state.put("status", appointment.getStatus().name());
        state.put("startsAt", appointment.getStartsAt().toString());
        state.put("durationMinutes", appointment.getDurationMinutes());
        return state;
    }

    private String json(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Appointment " + state.get("id") + " could not be serialized for the audit log", e);
        }
    }
}
