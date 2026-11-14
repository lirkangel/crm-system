package com.crm.dental.scan;

import com.crm.cccd.CccdData;
import com.crm.cccd.CccdQrParser;
import com.crm.cccd.ImageFormat;
import com.crm.cccd.QrDigest;
import com.crm.cccd.ScanBusyException;
import com.crm.cccd.ScanPipeline;
import com.crm.dental.DentalErrors;
import com.crm.dental.appointment.Appointment;
import com.crm.dental.appointment.AppointmentRepository;
import com.crm.dental.link.VisitLinkRepository;
import com.crm.foundation.Plugin.AuditRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Upload of a CCCD scan: the QR is decoded and parsed outside any transaction (no lock held while decoding), then
 * {@link DentalIdScanStore} stores the downscaled image in one short transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DentalIdScanService {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final String AUDIT_PURGE = "ID_SCAN_PURGE";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock dentalClock;
    private final AppointmentRepository appointments;
    private final DentalIdScanStore store;
    private final DentalIdScanRepository scans;
    private final VisitLinkRepository visitLinks;
    private final ScanPipeline pipeline;
    private final AuditRecorder audit;

    /** The stored scan's id, the fields its QR decoded to (for prefilling the form) and whether it is new. */
    public record UploadResult(UUID scanId, ScanFields fields, boolean created) {
    }

    /**
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404); {@code DENTAL_VISIT_CLOSED}
     *         (409) unless the appointment is {@code BOOKED}; {@code DENTAL_SCAN_UNSUPPORTED} (413 over 10 MB, 415
     *         for other types than JPEG/PNG); {@code DENTAL_SCAN_UNREADABLE} (400: no CCCD QR, or an unreadable or
     *         oversized image; nothing is stored); {@code DENTAL_SCAN_BUSY} (503); {@code DENTAL_SCAN_LIMIT} (409)
     */
    public UploadResult upload(UUID appointmentId, byte[] bytes) {
        return upload(appointmentId, null, bytes);
    }

    /**
     * An upload through a patient's link: as {@link #upload(UUID, byte[])}, and the link (identified by the hash of
     * the token the request resolved with) is re-checked under the appointment lock when the scan is stored.
     */
    public UploadResult uploadViaLink(UUID appointmentId, byte[] tokenHash, byte[] bytes) {
        return upload(appointmentId, java.util.Objects.requireNonNull(tokenHash), bytes);
    }

    private UploadResult upload(UUID appointmentId, byte[] tokenHash, byte[] bytes) {
        requireOpen(appointments.findById(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found")));
        if (bytes.length > MAX_BYTES) {
            throw DentalErrors.scanTooLarge("The image is over 10 MB");
        }
        if (ImageFormat.detect(bytes).isEmpty()) {
            throw DentalErrors.scanUnsupportedType("Only JPEG and PNG images are accepted");
        }
        ScanPipeline.Processed processed;
        try {
            processed = pipeline.process(bytes).orElseThrow(
                () -> DentalErrors.scanUnreadable("No QR code found: photograph the side of the card that has the QR code"));
        } catch (ScanBusyException e) {
            throw DentalErrors.scanBusy("The scanner is busy, try again in a moment");
        }
        CccdData data = CccdQrParser.parse(processed.qrText(), LocalDate.now(dentalClock)).orElseThrow(
            () -> DentalErrors.scanUnreadable("The QR code is not a citizen ID card's"));
        ScanFields fields = ScanFields.of(data);

        DentalIdScanStore.StoreResult stored = store.store(
            appointmentId, tokenHash, sha256(bytes), "image/jpeg", processed.storedJpeg(),
            QrDigest.of(fields.digestFields()));
        return new UploadResult(stored.scanId(), fields, stored.created());
    }

    /**
     * Deletes every scan uploaded before {@code cutoff} in one bulk statement in one transaction (no entity is
     * loaded, so no image bytes are read), and the visit links that expired before it (table hygiene, not counted).
     * A form whose scan is purged keeps its answers ({@code ON DELETE SET NULL}). Audited as {@code ID_SCAN_PURGE}
     * (counts only: {@code {"count": scans}}) when anything was deleted; a system job, so there is no user or IP.
     *
     * @return the number of deleted scans
     */
    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        int count = scans.deleteUploadedBefore(cutoff);
        visitLinks.deleteExpiredBefore(cutoff);
        if (count > 0) {
            audit.record(DentalIdScanStore.ID_SCAN, null, AUDIT_PURGE, null, "{\"count\": " + count + "}");
            log.info("Purged {} ID scan(s)", count);
        }
        return count;
    }

    static void requireOpen(Appointment appointment) {
        if (!DentalIdScanStore.accepts(appointment.getStatus())) {
            throw DentalErrors.visitClosed("The pre-visit form is closed for this appointment");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
