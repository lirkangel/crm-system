package com.crm.dental.previsit;

import com.crm.cccd.MaskedName;
import com.crm.dental.DentalConfig;
import com.crm.dental.DentalErrors;
import com.crm.dental.DentalSite;
import com.crm.dental.appointment.Appointment;
import com.crm.dental.appointment.AppointmentStatus;
import com.crm.dental.link.VisitLink;
import com.crm.dental.link.VisitLinkRepository;
import com.crm.dental.link.VisitLinkService;
import com.crm.dental.patient.Patient;
import com.crm.dental.patient.PatientRepository;
import com.crm.dental.scan.DentalIdScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * What a patient does with their personal link: read the visit, upload a CCCD photo, submit the form and, as a
 * returning patient, get earlier answers back. Every call resolves the appointment ONLY through
 * {@link VisitLinkService#resolve} (header token; every bad token is the same 404). A link whose appointment is no
 * longer {@code BOOKED} still resolves: the summary says {@code open: false} and every write is
 * {@code DENTAL_VISIT_CLOSED} (409). Not transactional itself (an upload's decode must not hold a lock); writes run
 * in {@link PrevisitStore}'s transactions.
 */
@Service
@RequiredArgsConstructor
public class PrevisitService {

    private final VisitLinkService visitLinks;
    private final VisitLinkRepository links;
    private final DentalSite dentalSite;
    private final PatientRepository patients;
    private final PrevisitFormRepository forms;
    private final DentalIdScanService scans;
    private final PrevisitStore store;

    /** A submission's outcome: 201 when the form was created, 200 when it replaced an earlier one. */
    public record SubmitResult(boolean created) {
    }

    @Transactional(readOnly = true)
    public VisitSummary summary(String token) {
        Appointment appointment = visitLinks.resolve(token);
        Patient patient = patients.findById(appointment.getPatientId())
            .orElseThrow(() -> DentalErrors.notFound("Link not found"));
        VisitLink link = links.findById(appointment.getId()).orElseThrow(() -> DentalErrors.notFound("Link not found"));
        VisitSummary.Returning returning = !forms.existsAnswersOfPatient(patient.getId()) ? VisitSummary.Returning.NONE
            : link.getDobAttempts() >= PrevisitStore.MAX_DOB_ATTEMPTS ? VisitSummary.Returning.LOCKED
            : VisitSummary.Returning.AVAILABLE;
        LocalDate visitDate = appointment.getStartsAt().atZone(DentalConfig.CLINIC_ZONE).toLocalDate();
        OffsetDateTime startsAt = appointment.getStartsAt().atZone(DentalConfig.CLINIC_ZONE).toOffsetDateTime();
        return new VisitSummary(dentalSite.displayName(), startsAt, appointment.getDurationMinutes(),
            appointment.getDentist(), MaskedName.of(patient.getFullName()),
            appointment.getStatus() == AppointmentStatus.BOOKED, forms.existsForAppointment(appointment.getId()),
            returning, PrevisitRules.isChild(patient.getDateOfBirth(), visitDate));
    }

    /**
     * A CCCD photo for the appointment, only while it is {@code BOOKED}.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND}; {@code DENTAL_VISIT_CLOSED} (409);
     *         the scan errors of {@link DentalIdScanService#upload}
     */
    public DentalIdScanService.UploadResult upload(String token, byte[] bytes) {
        return scans.uploadViaLink(visitLinks.resolve(token).getId(), VisitLinkService.hashOf(token), bytes);
    }

    /**
     * Creates or replaces the form.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND}; {@code DENTAL_VISIT_CLOSED} (409);
     *         {@code DENTAL_INVALID_FORM} (400)
     */
    public SubmitResult submit(String token, PrevisitRequest request) {
        return new SubmitResult(store.submit(visitLinks.resolve(token).getId(), VisitLinkService.hashOf(token), request).created());
    }

    /**
     * The returning-patient check.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND}; {@code DENTAL_VISIT_CLOSED} (409);
     *         {@code DENTAL_CHECK_FAILED} (403, wrong date); {@code DENTAL_CHECK_LOCKED} (423, 5 wrong dates, also
     *         for the right date afterwards)
     */
    public PreviousAnswers previous(String token, PreviousRequest request) {
        PrevisitStore.Outcome checked = store.previous(visitLinks.resolve(token).getId(),
            VisitLinkService.hashOf(token), request == null ? null : request.dateOfBirth());
        return switch (checked.result()) {
            case MATCH -> checked.answers();
            case WRONG -> throw DentalErrors.checkFailed("That date of birth does not match");
            case LOCKED -> throw DentalErrors.checkLocked("Too many wrong answers: the check is locked for this link");
        };
    }
}
