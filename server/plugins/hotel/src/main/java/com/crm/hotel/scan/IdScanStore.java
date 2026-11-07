package com.crm.hotel.scan;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.repo.IdScanRepository;
import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * The short transaction of a scan upload, separate from {@link IdScanService} so the (slow, CPU-bound) QR decode
 * runs outside it and never holds the reservation lock.
 */
@Service
@RequiredArgsConstructor
class IdScanStore {

    static final int MAX_SCANS_PER_RESERVATION = 20;
    static final String ID_SCAN = "IdScan";
    static final String AUDIT_UPLOAD = "ID_SCAN_UPLOAD";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final ReservationRepository reservations;
    private final IdScanRepository scans;
    private final CurrentUser currentUser;
    private final AuditRecorder audit;

    /** Stores the scan, or returns the id of the reservation's existing scan of the same original bytes. */
    @Transactional
    StoreResult store(UUID reservationId, byte[] sha256, String contentType, byte[] jpeg) {
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        IdScanService.requireOpen(reservation);

        Optional<UUID> existing = scans.findIdByReservationIdAndSha256(reservationId, sha256);
        if (existing.isPresent()) {
            return new StoreResult(existing.get(), false);
        }
        if (scans.countByReservationId(reservationId) >= MAX_SCANS_PER_RESERVATION) {
            throw HotelErrors.scanLimit("A reservation holds at most " + MAX_SCANS_PER_RESERVATION + " scans");
        }
        IdScan scan = new IdScan();
        scan.setReservationId(reservationId);
        scan.setContentType(contentType);
        scan.setImage(jpeg);
        scan.setSha256(sha256);
        scan.setSizeBytes(jpeg.length);
        scan.setUploadedBy(currentUser.id().orElse(null));
        // Microsecond precision, as stored by timestamptz
        scan.setUploadedAt(Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS));
        IdScan saved = scans.saveAndFlush(scan);
        // Only the stored size: the audit chain is append-only, so never the image or anything decoded from it.
        audit.record(ID_SCAN, saved.getId(), AUDIT_UPLOAD, null, "{\"sizeBytes\":" + saved.getSizeBytes() + "}");
        return new StoreResult(saved.getId(), true);
    }

    record StoreResult(UUID scanId, boolean created) {
    }

    /** Package-visible so the service can name the status rule once. */
    static boolean accepts(ReservationStatus status) {
        return status == ReservationStatus.CONFIRMED || status == ReservationStatus.CHECKED_IN;
    }
}
