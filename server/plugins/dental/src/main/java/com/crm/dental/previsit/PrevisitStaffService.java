package com.crm.dental.previsit;

import com.crm.dental.DentalErrors;
import com.crm.dental.IfMatch;
import com.crm.dental.appointment.Appointment;
import com.crm.dental.appointment.AppointmentRepository;
import com.crm.dental.appointment.AppointmentStatus;
import com.crm.dental.patient.Patient;
import com.crm.dental.patient.PatientRepository;
import com.crm.dental.patient.PatientRequest;
import com.crm.dental.patient.PatientService;
import com.crm.dental.patient.PatientView;
import com.crm.dental.scan.DentalIdScanRepository;
import com.crm.dental.scan.StoredImage;
import com.crm.foundation.Plugin.AuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * What staff do with a submitted form. Reading decrypts the health answers and is audited as {@code PREVISIT_VIEW}
 * (staff user and appointment id, never a value); the photo is read by a projection scoped to the appointment's
 * form; applying copies the confirmed identity and phone to the patient record.
 */
@Service
@RequiredArgsConstructor
public class PrevisitStaffService {

    static final String AUDIT_VIEW = "PREVISIT_VIEW";

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final PrevisitFormRepository forms;
    private final DentalIdScanRepository idScans;
    private final PatientService patientService;
    private final PrevisitStore store;
    private final AuditRecorder audit;

    /**
     * The decrypted form. The view is audited in the same transaction, after the answers were read: a form that
     * cannot be read, or does not exist, leaves no audit row.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404) without a form;
     *         {@code DENTAL_FIELD_UNREADABLE} (500) when stored answers fail authentication
     */
    @Transactional
    public PrevisitView read(UUID appointmentId) {
        PrevisitForm form = forms.findByAppointment(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("No pre-visit form for this appointment"));
        Patient patient = patients.findById(form.getPatientId())
            .orElseThrow(() -> DentalErrors.notFound("No pre-visit form for this appointment"));
        PrevisitRequest.Identity identity = form.getFullName() == null ? null : new PrevisitRequest.Identity(
            form.getIdType(), form.getIdNumber(), form.getFullName(), form.getDateOfBirth(), form.getNationality(),
            form.getAddress());
        PrevisitView view = new PrevisitView(appointmentId, form.getPatientId(), patient.getVersion(), identity,
            form.getParentName(), form.getParentPhone(), form.getPhone(),
            open(form.getAllergiesEnc(), appointmentId, PrevisitStore.COL_ALLERGIES, AllergyAnswer.class),
            open(form.getMedicinesEnc(), appointmentId, PrevisitStore.COL_MEDICINES, MedicineAnswer.class),
            open(form.getReasonEnc(), appointmentId, PrevisitStore.COL_REASON, ReasonAnswer.class),
            form.getIdScanId() != null, editedFields(form.getEditedFields()), form.getConsentAt(),
            form.getNoticeVersion(), form.getSubmittedAt());
        audit.record(PrevisitStore.APPOINTMENT, appointmentId, AUDIT_VIEW, null, null);
        return view;
    }

    /**
     * The stored photo, only through the appointment's own form (the entity is never loaded).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404) without a form or a photo
     */
    @Transactional(readOnly = true)
    public StoredImage image(UUID appointmentId) {
        return idScans.findFormImage(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("No identity photo for this appointment"));
    }

    /**
     * Copies the form's identity (when it has one) and phone into the patient record. The appointment is locked
     * first, then the patient row; the appointment must still be {@code BOOKED} or {@code ARRIVED}.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404); {@code DENTAL_INVALID_STATE}
     *         (409) for another status or without a form; {@code DENTAL_VERSION_REQUIRED} (428); a stale
     *         {@code If-Match} is a 409
     */
    @Transactional
    public PatientView apply(UUID appointmentId, String ifMatch) {
        Appointment appointment = appointments.lockById(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
        if (appointment.getStatus() != AppointmentStatus.BOOKED && appointment.getStatus() != AppointmentStatus.ARRIVED) {
            throw DentalErrors.invalidState("A pre-visit form can be applied only for a booked or arrived appointment; this one is "
                + appointment.getStatus());
        }
        PrevisitForm form = forms.findByAppointment(appointmentId)
            .orElseThrow(() -> DentalErrors.invalidState("This appointment has no pre-visit form to apply"));
        Patient patient = patients.lockById(appointment.getPatientId())
            .orElseThrow(() -> DentalErrors.notFound("Patient not found"));
        IfMatch.requireVersion(ifMatch, patient.getVersion(), Patient.class, patient.getId());
        PatientRequest request = form.getFullName() == null
            ? new PatientRequest(patient.getFullName(), form.getPhone(), patient.getDateOfBirth(), patient.getIdType(),
                patient.getIdNumber(), patient.getNationality(), patient.getAddress())
            : new PatientRequest(form.getFullName(), form.getPhone(), form.getDateOfBirth(), form.getIdType(),
                form.getIdNumber(), form.getNationality(), form.getAddress());
        return patientService.applyTo(patient, request);
    }

    private <T> T open(byte[] sealed, UUID appointmentId, String column, Class<T> type) {
        return sealed == null ? null : store.open(sealed, appointmentId, column, type);
    }

    private static List<String> editedFields(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }
}
