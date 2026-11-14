package com.crm.dental.previsit;

import com.crm.cccd.QrDigest;
import com.crm.dental.DentalConfig;
import com.crm.dental.DentalErrors;
import com.crm.dental.appointment.Appointment;
import com.crm.dental.appointment.AppointmentRepository;
import com.crm.dental.appointment.AppointmentStatus;
import com.crm.dental.crypto.FieldCipher;
import com.crm.dental.link.VisitLink;
import com.crm.dental.link.VisitLinkRepository;
import com.crm.dental.link.VisitLinkService;
import com.crm.dental.patient.Patient;
import com.crm.dental.patient.PatientRepository;
import com.crm.dental.scan.DentalIdScanRepository;
import com.crm.foundation.Plugin.AuditRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transactions of the public form, separate from {@link PrevisitService} so the appointment is locked (and read)
 * in a transaction of its own. Every method locks the appointment first and re-checks that it is still
 * {@code BOOKED}; health answers are encrypted here and decrypted only in {@link #previous}, after the date matched.
 */
@Service
@RequiredArgsConstructor
class PrevisitStore {

    static final String APPOINTMENT = "Appointment";
    static final String AUDIT_SUBMIT = "PREVISIT_SUBMIT";
    /** The privacy notice the patient consents to. */
    static final String NOTICE_VERSION = "2026-11-14";
    /** Wrong dates of birth after which a link's previous-answers check is locked. */
    static final int MAX_DOB_ATTEMPTS = 5;
    static final String COL_ALLERGIES = "allergies_enc";
    static final String COL_MEDICINES = "medicines_enc";
    static final String COL_REASON = "reason_enc";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock dentalClock;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final PrevisitFormRepository forms;
    private final DentalIdScanRepository scans;
    private final VisitLinkService visitLinks;
    private final VisitLinkRepository links;
    private final FieldCipher fieldCipher;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;

    record Submitted(boolean created) {
    }

    /** What the date-of-birth check came to; {@code answers} only for {@code MATCH}. */
    record Outcome(Result result, PreviousAnswers answers) {
        enum Result { MATCH, WRONG, LOCKED }
    }

    /**
     * Under the appointment lock: re-checks {@code BOOKED}, validates, then creates the form or replaces the
     * existing one.
     */
    @Transactional
    Submitted submit(UUID appointmentId, byte[] tokenHash, PrevisitRequest request) {
        Appointment appointment = lockOpen(appointmentId, tokenHash).appointment();
        Patient patient = patients.findById(appointment.getPatientId())
            .orElseThrow(() -> DentalErrors.notFound("Link not found"));
        LocalDate visitDate = appointment.getStartsAt().atZone(DentalConfig.CLINIC_ZONE).toLocalDate();
        boolean child = PrevisitRules.isChild(patient.getDateOfBirth(), visitDate);
        PrevisitRules.Checked checked = PrevisitRules.check(request, child, LocalDate.now(dentalClock));

        UUID scanId = request.idScanId();
        String editedFields = null;
        if (scanId != null) {
            UUID owner = scans.findAppointmentIdById(scanId).orElse(null);
            if (!appointmentId.equals(owner)) {
                throw DentalErrors.invalidForm("The scan does not belong to this appointment");
            }
            if (checked.identity() != null) {
                List<String> changed = QrDigest.changed(
                    scans.findQrDigestById(scanId).orElse(null), digestFields(checked.identity()));
                editedFields = changed.isEmpty() ? null : String.join(",", changed);
            }
        }

        PrevisitForm form = forms.findByAppointment(appointmentId).orElse(null);
        boolean created = form == null;
        if (created) {
            form = new PrevisitForm();
            form.setAppointmentId(appointmentId);
        }
        Instant now = Instant.now(dentalClock).truncatedTo(ChronoUnit.MICROS);
        form.setPatientId(appointment.getPatientId());
        PrevisitRequest.Identity identity = checked.identity();
        form.setFullName(identity == null ? null : identity.fullName());
        form.setDateOfBirth(identity == null ? null : identity.dateOfBirth());
        form.setIdType(identity == null ? null : identity.idType());
        form.setIdNumber(identity == null ? null : identity.idNumber());
        form.setNationality(identity == null ? null : identity.nationality());
        form.setAddress(identity == null ? null : identity.address());
        form.setParentName(checked.parentName());
        form.setParentPhone(checked.parentPhone());
        form.setPhone(checked.phone());
        form.setAllergiesEnc(seal(checked.allergies(), appointmentId, COL_ALLERGIES));
        form.setMedicinesEnc(seal(checked.medicines(), appointmentId, COL_MEDICINES));
        form.setReasonEnc(seal(checked.reason(), appointmentId, COL_REASON));
        form.setIdScanId(scanId);
        form.setEditedFields(editedFields);
        form.setConsentAt(now);
        form.setNoticeVersion(NOTICE_VERSION);
        form.setSubmittedAt(now);
        forms.saveAndFlush(form);
        // The appointment id only: the audit chain is append-only, so no values and no token.
        audit.record(APPOINTMENT, appointmentId, AUDIT_SUBMIT, null, null);
        return new Submitted(created);
    }

    /**
     * The date-of-birth check, under the appointment lock so concurrent guesses cannot exceed the limit. A wrong
     * date is counted and committed (the outcome is returned, not thrown, so the count is not rolled back); a locked
     * link refuses even the right date.
     */
    @Transactional
    Outcome previous(UUID appointmentId, byte[] tokenHash, LocalDate dateOfBirth) {
        Locked locked = lockOpen(appointmentId, tokenHash);
        Appointment appointment = locked.appointment();
        VisitLink link = locked.link();
        Patient patient = patients.findById(appointment.getPatientId())
            .orElseThrow(() -> DentalErrors.notFound("Link not found"));
        // No answers at all: 404 before the date is looked at and without counting (no date-of-birth oracle)
        if (!forms.existsAnswersOfPatient(patient.getId())) {
            throw DentalErrors.notFound("No earlier answers");
        }
        if (dateOfBirth == null) {
            throw DentalErrors.invalidForm("The date of birth is required");
        }
        if (link.getDobAttempts() >= MAX_DOB_ATTEMPTS) {
            return new Outcome(Outcome.Result.LOCKED, null);
        }
        if (!patient.getDateOfBirth().equals(dateOfBirth)) {
            link.setDobAttempts(link.getDobAttempts() + 1);
            links.saveAndFlush(link);
            return new Outcome(link.getDobAttempts() >= MAX_DOB_ATTEMPTS ? Outcome.Result.LOCKED : Outcome.Result.WRONG, null);
        }
        PrevisitForm own = forms.findByAppointment(appointmentId).filter(f -> f.getAllergiesEnc() != null).orElse(null);
        PrevisitForm source = own != null ? own : forms.findEarlierOfPatient(
            patient.getId(), appointmentId, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (source == null) {
            throw DentalErrors.notFound("No earlier answers");
        }
        return new Outcome(Outcome.Result.MATCH, new PreviousAnswers(
            open(source.getAllergiesEnc(), source.getAppointmentId(), COL_ALLERGIES, AllergyAnswer.class),
            open(source.getMedicinesEnc(), source.getAppointmentId(), COL_MEDICINES, MedicineAnswer.class),
            open(source.getReasonEnc(), source.getAppointmentId(), COL_REASON, ReasonAnswer.class),
            own != null));
    }

    private record Locked(Appointment appointment, VisitLink link) {
    }

    /**
     * Locks the appointment, then re-checks the link the request resolved with (same 404 if it is gone, replaced
     * or expired), then that the appointment is still {@code BOOKED}.
     */
    private Locked lockOpen(UUID appointmentId, byte[] tokenHash) {
        Appointment appointment = appointments.lockById(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("Link not found"));
        VisitLink link = visitLinks.requireLive(appointmentId, tokenHash);
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw DentalErrors.visitClosed("The pre-visit form is closed for this appointment");
        }
        return new Locked(appointment, link);
    }

    /** The AAD binds a ciphertext to its appointment and column, so copying it elsewhere fails closed. */
    private static byte[] aad(UUID appointmentId, String column) {
        return (appointmentId + ":" + column).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] seal(Object answer, UUID appointmentId, String column) {
        try {
            return fieldCipher.encrypt(objectMapper.writeValueAsString(answer), aad(appointmentId, column));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An answer could not be serialized");
        }
    }

    private <T> T open(byte[] sealed, UUID appointmentId, String column, Class<T> type) {
        try {
            return objectMapper.readValue(fieldCipher.decrypt(sealed, aad(appointmentId, column)), type);
        } catch (JsonProcessingException | IllegalStateException e) {
            // Tampered, copied from another row, or unreadable: nothing is returned, and nothing is logged of it
            throw DentalErrors.fieldUnreadable();
        }
    }

    /** The submitted identity under {@link QrDigest}'s field names, dates in ISO form. */
    private static Map<String, String> digestFields(PrevisitRequest.Identity identity) {
        Map<String, String> fields = new HashMap<>();
        fields.put("idType", identity.idType());
        fields.put("idNumber", identity.idNumber());
        fields.put("fullName", identity.fullName());
        fields.put("dateOfBirth", identity.dateOfBirth() == null ? null : identity.dateOfBirth().toString());
        fields.put("nationality", identity.nationality());
        fields.put("address", identity.address());
        return fields;
    }
}
