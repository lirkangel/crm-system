package com.crm.hotel.checkin;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.domain.Room;
import com.crm.hotel.domain.RoomType;
import com.crm.hotel.repo.IdScanRepository;
import com.crm.hotel.repo.PreArrivalGuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import com.crm.hotel.repo.RoomRepository;
import com.crm.hotel.repo.RoomTypeRepository;
import com.crm.hotel.scan.QrDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactions of a guest's submissions, separate from {@link PreArrivalService} so the reservation row is locked
 * (and read) inside a transaction of its own, never from a persistence context that already holds an older copy.
 */
@Service
@RequiredArgsConstructor
class PreArrivalStore {

    static final String RESERVATION = "Reservation";
    static final String AUDIT_SUBMIT = "PRE_ARRIVAL_SUBMIT";
    static final String AUDIT_DELETE = "PRE_ARRIVAL_DELETE";
    /** The privacy notice the guest consents to. */
    static final String NOTICE_VERSION = "2026-11-14";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final ReservationRepository reservations;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final PreArrivalGuestRepository submissions;
    private final IdScanRepository scans;
    private final AuditRecorder audit;

    record Submitted(PreArrivalGuest guest, boolean created) {
    }

    /**
     * Under the reservation lock: re-checks that online check-in is open, returns the existing submission of the
     * same document, otherwise checks the room's capacity and the scan, then inserts.
     */
    @Transactional
    Submitted submit(UUID reservationId, PreArrivalRequest request) {
        Reservation reservation = lockOpen(reservationId);
        var identity = request.identity();
        String idNumber = identity.idNumber().trim();

        // A repeat of a document is answered with what is stored, even when the room is full
        Optional<PreArrivalGuest> existing = submissions.findByReservationIdAndIdTypeAndIdNumber(
            reservationId, identity.idType(), idNumber);
        if (existing.isPresent()) {
            return new Submitted(existing.get(), false);
        }
        int maxGuests = maxGuests(reservation);
        if (submissions.countByReservationId(reservationId) >= maxGuests) {
            throw HotelErrors.tooManyGuests("At most " + maxGuests + " guests for this room type");
        }
        UUID scanId = identity.idScanId();
        String editedFields = null;
        if (scanId != null) {
            IdScanRepository.Link scan = scans.findLinkById(scanId)
                .filter(link -> link.getReservationId().equals(reservationId) && link.getStayGuestId() == null)
                .orElseThrow(() -> HotelErrors.scanMismatch("The scan does not belong to this reservation"));
            if (submissions.existsByIdScanId(scanId)) {
                throw HotelErrors.scanMismatch("The scan is already used by another guest");
            }
            List<String> changed = QrDigest.changed(scans.findQrDigestById(scanId).orElse(null), identity);
            editedFields = changed.isEmpty() ? null : String.join(",", changed);
        }

        Instant now = Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS);
        PreArrivalGuest guest = new PreArrivalGuest();
        guest.setReservationId(reservationId);
        guest.setIdType(identity.idType());
        guest.setIdNumber(idNumber);
        guest.setFullName(identity.fullName().trim());
        guest.setDateOfBirth(identity.dateOfBirth());
        guest.setNationality(identity.nationality());
        guest.setAddress(blankToNull(identity.address()));
        guest.setVisaNumber(blankToNull(identity.visaNumber()));
        guest.setEntryDate(identity.entryDate());
        guest.setIdScanId(scanId);
        guest.setEditedFields(editedFields);
        guest.setConsentAt(now);
        guest.setNoticeVersion(NOTICE_VERSION);
        guest.setSubmittedAt(now);
        PreArrivalGuest saved = submissions.saveAndFlush(guest);
        // Only the id: the audit chain is append-only, so no personal data.
        audit.record(RESERVATION, reservationId, AUDIT_SUBMIT, null, submissionJson(saved.getId()));
        return new Submitted(saved, true);
    }

    /** Deletes the submission, then its scan (bulk statements: the scan's image is never loaded). */
    @Transactional
    void delete(UUID reservationId, UUID submissionId) {
        lockOpen(reservationId);
        PreArrivalGuestRepository.ScanRef ref = submissions.findScanRef(submissionId, reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Submission not found"));
        if (submissions.deleteOne(submissionId, reservationId) != 1) {
            throw HotelErrors.notFound("Submission not found");
        }
        if (ref.getIdScanId() != null) {
            scans.deleteUnlinked(ref.getIdScanId(), reservationId);
        }
        audit.record(RESERVATION, reservationId, AUDIT_DELETE, null, submissionJson(submissionId));
    }

    private Reservation lockOpen(UUID reservationId) {
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Link not found"));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw HotelErrors.checkinClosed("Online check-in is closed for this reservation");
        }
        return reservation;
    }

    private int maxGuests(Reservation reservation) {
        Room room = rooms.findById(reservation.getRoomId()).orElseThrow(() -> HotelErrors.notFound("Room not found"));
        RoomType type = roomTypes.findById(room.getRoomTypeId())
            .orElseThrow(() -> HotelErrors.notFound("Room type not found"));
        return type.getMaxGuests();
    }

    private static String submissionJson(UUID submissionId) {
        return "{\"submissionId\":\"" + submissionId + "\"}";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
