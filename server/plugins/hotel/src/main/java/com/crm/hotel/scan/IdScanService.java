package com.crm.hotel.scan;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.hotel.BusinessDay;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.repo.GuestLinkRepository;
import com.crm.hotel.repo.IdScanRepository;
import com.crm.hotel.repo.PreArrivalGuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload of a CCCD scan: the QR is decoded and parsed outside any transaction (no lock held while decoding), then
 * {@link IdScanStore} stores the downscaled image in one short transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdScanService {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final String AUDIT_PURGE = "ID_SCAN_PURGE";

    private final BusinessDay businessDay;
    private final ReservationRepository reservations;
    private final IdScanStore store;
    private final IdScanRepository scans;
    private final PreArrivalGuestRepository preArrivals;
    private final GuestLinkRepository guestLinks;
    private final ScanPipeline pipeline;
    private final AuditRecorder audit;

    public record UploadResult(IdScanView view, boolean created) {
    }

    /**
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless {@code CONFIRMED} or {@code CHECKED_IN}; {@code HOTEL_SCAN_UNSUPPORTED} (413 over 10 MB,
     *         415 for other types than JPEG/PNG); {@code HOTEL_SCAN_UNREADABLE} (400: no CCCD QR, or an unreadable
     *         or oversized image; nothing is stored); {@code HOTEL_SCAN_BUSY} (503); {@code HOTEL_SCAN_LIMIT} (409)
     */
    public UploadResult upload(UUID reservationId, byte[] bytes) {
        return upload(reservationId, bytes, false);
    }

    /**
     * An upload through a guest's personal link: as {@link #upload(UUID, byte[])}, but only while the reservation is
     * {@code CONFIRMED} (online check-in is open), otherwise {@code HOTEL_CHECKIN_CLOSED} (409) instead of
     * {@code HOTEL_INVALID_STATE}.
     */
    public UploadResult uploadViaLink(UUID reservationId, byte[] bytes) {
        return upload(reservationId, bytes, true);
    }

    private UploadResult upload(UUID reservationId, byte[] bytes, boolean guestLink) {
        requireOpen(reservations.findById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found")), guestLink);
        if (bytes.length > MAX_BYTES) {
            throw HotelErrors.scanTooLarge("The image is over 10 MB");
        }
        if (ImageFormat.detect(bytes).isEmpty()) {
            throw HotelErrors.scanUnsupportedType("Only JPEG and PNG images are accepted");
        }
        ScanPipeline.Processed processed;
        try {
            processed = pipeline.process(bytes).orElseThrow(
                () -> HotelErrors.scanUnreadable("No QR code found: photograph the side of the card that has the QR code"));
        } catch (ScanBusyException e) {
            throw HotelErrors.scanBusy("The scanner is busy, try again in a moment");
        }
        CccdData data = CccdQrParser.parse(processed.qrText(), businessDay.today()).orElseThrow(
            () -> HotelErrors.scanUnreadable("The QR code is not a citizen ID card's"));
        byte[] jpeg = processed.storedJpeg();

        IdScanStore.StoreResult stored = store.store(
            reservationId, sha256(bytes), "image/jpeg", jpeg, QrDigest.of(ScanFields.of(data)), guestLink);
        return new UploadResult(new IdScanView(stored.scanId(), ScanFields.of(data)), stored.created());
    }

    /**
     * Links a scan to a stay guest, inside the caller's check-in / correction transaction (the reservation is
     * already locked and the stay guest row already flushed). Any other scan the stay guest had is deleted first, so
     * a correction replaces its scan; linking the scan it already has is a no-op.
     *
     * @return whether the link changed
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_SCAN_MISMATCH} (409) for an unknown scan, one of
     *         another reservation, or one already linked to another stay guest
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean link(UUID reservationId, UUID scanId, UUID stayGuestId) {
        IdScanRepository.Link scan = scans.findLinkById(scanId)
            .orElseThrow(() -> HotelErrors.scanMismatch("The scan does not belong to this stay"));
        if (!scan.getReservationId().equals(reservationId)) {
            throw HotelErrors.scanMismatch("The scan does not belong to this stay");
        }
        if (scan.getStayGuestId() != null) {
            if (scan.getStayGuestId().equals(stayGuestId)) {
                return false;
            }
            throw HotelErrors.scanMismatch("The scan is already linked to another guest");
        }
        // Delete first (flushed), so the UNIQUE(stay_guest_id) can't trip when the scan is linked
        scans.deleteLinkedToExcept(stayGuestId, scanId);
        if (scans.linkTo(scanId, stayGuestId) != 1) {
            // The retention purge takes no reservation lock: the scan was deleted since it was read
            throw HotelErrors.scanMismatch("The scan no longer exists");
        }
        return true;
    }

    /** Deletes the scans linked to these stay guests, before the stay guests themselves are deleted (plain FK). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unlinkAndDelete(Collection<UUID> stayGuestIds) {
        if (!stayGuestIds.isEmpty()) {
            scans.deleteLinkedToAny(stayGuestIds);
        }
    }

    /**
     * Deletes every scan uploaded before {@code cutoff} and every pre-arrival submission made before it, each in one
     * bulk statement in one transaction (no entity is loaded, so no image bytes are read), and the guest links that
     * expired before it (table hygiene, not counted). A scan purged before its younger submission leaves the submission with no scan ({@code ON DELETE SET NULL}). Audited as
     * {@code ID_SCAN_PURGE} (counts only: {@code {"count": scans, "preArrival": submissions}}) when anything was
     * deleted; a system job, so there is no user or IP.
     *
     * @return the number of deleted scans
     */
    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        int preArrival = preArrivals.deleteSubmittedBefore(cutoff);
        int count = scans.deleteUploadedBefore(cutoff);
        // Table hygiene: a link that expired more than the retention period ago is useless (not audited)
        guestLinks.deleteExpiredBefore(cutoff);
        if (count > 0 || preArrival > 0) {
            audit.record(IdScanStore.ID_SCAN, null, AUDIT_PURGE, null,
                "{\"count\": " + count + ", \"preArrival\": " + preArrival + "}");
            log.info("Purged {} ID scan(s) and {} pre-arrival submission(s)", count, preArrival);
        }
        return count;
    }

    /** The image linked to this stay guest of this reservation, if any. */
    @Transactional(readOnly = true)
    public Optional<StoredImage> image(UUID reservationId, UUID stayGuestId) {
        return scans.findImage(reservationId, stayGuestId);
    }

    static void requireOpen(Reservation reservation, boolean guestLink) {
        if (guestLink) {
            if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
                throw HotelErrors.checkinClosed("Online check-in is closed for this reservation");
            }
        } else if (!IdScanStore.accepts(reservation.getStatus())) {
            throw HotelErrors.invalidState(
                "Scans can only be added to a confirmed or checked-in stay; this one is " + reservation.getStatus());
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
