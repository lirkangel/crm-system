package com.crm.hotel.scan;

import com.crm.hotel.BusinessDay;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Upload of a CCCD scan: the QR is decoded and parsed outside any transaction (no lock held while decoding), then
 * {@link IdScanStore} stores the downscaled image in one short transaction.
 */
@Service
@RequiredArgsConstructor
public class IdScanService {

    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final BusinessDay businessDay;
    private final ReservationRepository reservations;
    private final IdScanStore store;

    public record UploadResult(IdScanView view, boolean created) {
    }

    /**
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless {@code CONFIRMED} or {@code CHECKED_IN}; {@code HOTEL_SCAN_UNSUPPORTED} (413 over 10 MB,
     *         415 for other types than JPEG/PNG); {@code HOTEL_SCAN_UNREADABLE} (400: no CCCD QR, or an unreadable
     *         or oversized image; nothing is stored); {@code HOTEL_SCAN_BUSY} (503); {@code HOTEL_SCAN_LIMIT} (409)
     */
    public UploadResult upload(UUID reservationId, byte[] bytes) {
        requireOpen(reservations.findById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found")));
        if (bytes.length > MAX_BYTES) {
            throw HotelErrors.scanTooLarge("The image is over 10 MB");
        }
        if (ImageFormat.detect(bytes).isEmpty()) {
            throw HotelErrors.scanUnsupportedType("Only JPEG and PNG images are accepted");
        }
        String text;
        try {
            text = QrDecoder.decode(bytes).orElseThrow(
                () -> HotelErrors.scanUnreadable("No QR code found: photograph the back of the card"));
        } catch (ScanBusyException e) {
            throw HotelErrors.scanBusy("The scanner is busy, try again in a moment");
        }
        CccdData data = CccdQrParser.parse(text, businessDay.today()).orElseThrow(
            () -> HotelErrors.scanUnreadable("The QR code is not a citizen ID card's"));
        byte[] jpeg;
        try {
            jpeg = ImageNormalizer.toStoredJpeg(bytes);
        } catch (IllegalArgumentException e) {
            throw HotelErrors.scanUnreadable("The image could not be read");
        }

        IdScanStore.StoreResult stored = store.store(reservationId, sha256(bytes), "image/jpeg", jpeg);
        return new UploadResult(new IdScanView(stored.scanId(), ScanFields.of(data)), stored.created());
    }

    static void requireOpen(Reservation reservation) {
        if (!IdScanStore.accepts(reservation.getStatus())) {
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
