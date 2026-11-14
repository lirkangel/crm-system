package com.crm.dental.scan;

import com.crm.dental.DentalErrors;
import com.crm.dental.appointment.Appointment;
import com.crm.dental.appointment.AppointmentRepository;
import com.crm.dental.appointment.AppointmentStatus;
import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * The short transaction of a scan upload, separate from {@link DentalIdScanService} so the (slow, CPU-bound) QR
 * decode runs outside it and never holds the appointment lock.
 */
@Service
@RequiredArgsConstructor
class DentalIdScanStore {

    static final int MAX_SCANS_PER_APPOINTMENT = 20;
    static final String ID_SCAN = "DentalIdScan";
    static final String AUDIT_UPLOAD = "ID_SCAN_UPLOAD";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock dentalClock;
    private final AppointmentRepository appointments;
    private final DentalIdScanRepository scans;
    private final CurrentUser currentUser;
    private final AuditRecorder audit;

    /** Stores the scan, or returns the id of the appointment's existing scan of the same original bytes. */
    @Transactional
    StoreResult store(UUID appointmentId, byte[] sha256, String contentType, byte[] jpeg, String qrDigest) {
        Appointment appointment = appointments.lockById(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
        DentalIdScanService.requireOpen(appointment);

        Optional<UUID> existing = scans.findIdByAppointmentAndSha256(appointmentId, sha256);
        if (existing.isPresent()) {
            return new StoreResult(existing.get(), false);
        }
        if (scans.countByAppointment(appointmentId) >= MAX_SCANS_PER_APPOINTMENT) {
            throw DentalErrors.scanLimit("An appointment holds at most " + MAX_SCANS_PER_APPOINTMENT + " scans");
        }
        DentalIdScan scan = new DentalIdScan();
        scan.setAppointmentId(appointmentId);
        scan.setContentType(contentType);
        scan.setImage(jpeg);
        scan.setSha256(sha256);
        scan.setSizeBytes(jpeg.length);
        scan.setQrDigest(qrDigest);
        scan.setUploadedBy(currentUser.id().orElse(null));
        // Microsecond precision, as stored by timestamptz
        scan.setUploadedAt(Instant.now(dentalClock).truncatedTo(ChronoUnit.MICROS));
        DentalIdScan saved = scans.saveAndFlush(scan);
        // Only the stored size: the audit chain is append-only, so never the image or anything decoded from it.
        audit.record(ID_SCAN, saved.getId(), AUDIT_UPLOAD, null, "{\"sizeBytes\":" + saved.getSizeBytes() + "}");
        return new StoreResult(saved.getId(), true);
    }

    record StoreResult(UUID scanId, boolean created) {
    }

    static boolean accepts(AppointmentStatus status) {
        return status == AppointmentStatus.BOOKED;
    }
}
