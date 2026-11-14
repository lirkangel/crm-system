package com.crm.hotel.checkin;

import com.crm.hotel.BusinessDay;
import com.crm.hotel.GuestSite;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.domain.Room;
import com.crm.hotel.domain.RoomType;
import com.crm.hotel.repo.PreArrivalGuestRepository;
import com.crm.hotel.repo.RoomRepository;
import com.crm.hotel.repo.RoomTypeRepository;
import com.crm.hotel.scan.IdScanService;
import com.crm.hotel.stay.CheckInPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a guest does with their personal link: read the stay, upload a CCCD photo, submit and withdraw identities.
 * Every call starts from the token (header {@code X-Checkin-Token}); a missing, blank, over-long, unknown, expired or
 * replaced token is the same {@code HOTEL_NOT_FOUND} (404). Not transactional itself: the decoding of an upload must
 * not hold a lock, and writes run in {@link PreArrivalStore}'s transactions.
 */
@Service
@RequiredArgsConstructor
public class PreArrivalService {

    /** Longer than any real token (43 characters), so oversize input is refused before it is hashed. */
    static final int MAX_TOKEN_LENGTH = 100;

    private final GuestLinkService links;
    private final GuestSite guestSite;
    private final BusinessDay businessDay;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final PreArrivalGuestRepository submissions;
    private final IdScanService scans;
    private final PreArrivalStore store;

    /** A submission and whether this call created it (a repeat of the same document returns the first). */
    public record SubmitResult(CheckinSummary.SubmittedGuest guest, boolean created) {
    }

    public CheckinSummary summary(String token) {
        Reservation reservation = reservationOf(token);
        Room room = rooms.findById(reservation.getRoomId()).orElseThrow(() -> HotelErrors.notFound("Link not found"));
        RoomType type = roomTypes.findById(room.getRoomTypeId())
            .orElseThrow(() -> HotelErrors.notFound("Link not found"));
        List<CheckinSummary.SubmittedGuest> guests = submissions
            .findByReservationIdOrderBySubmittedAtAscIdAsc(reservation.getId()).stream()
            .map(PreArrivalService::view).toList();
        return new CheckinSummary(guestSite.displayName(), roomTypeName(type), reservation.getCheckIn(),
            reservation.getCheckOut(), reservation.getAdults(), type.getMaxGuests(),
            reservation.getStatus() == ReservationStatus.CONFIRMED, guests);
    }

    /**
     * A CCCD photo for the reservation, only while online check-in is open.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND}; {@code HOTEL_CHECKIN_CLOSED} (409);
     *         the scan errors of {@link IdScanService#uploadViaLink}
     */
    public IdScanService.UploadResult upload(String token, byte[] bytes) {
        Reservation reservation = reservationOf(token);
        requireOpen(reservation);
        return scans.uploadViaLink(reservation.getId(), bytes);
    }

    /**
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND}; {@code HOTEL_INVALID_GUEST} (400)
     *         without consent; {@code HOTEL_INVALID_ID_DOC} (400); {@code HOTEL_CHECKIN_CLOSED} (409);
     *         {@code HOTEL_TOO_MANY_GUESTS} (400); {@code HOTEL_SCAN_MISMATCH} (409)
     */
    public SubmitResult submit(String token, PreArrivalRequest request) {
        Reservation reservation = reservationOf(token);
        if (!request.consent()) {
            throw HotelErrors.invalidGuest("Consent to the use of the identity details is required");
        }
        CheckInPolicy.checkIdentity(request.identity(), businessDay.calendarToday());
        PreArrivalStore.Submitted submitted = store.submit(reservation.getId(), request);
        return new SubmitResult(view(submitted.guest()), submitted.created());
    }

    /** Withdraws a submission of this reservation, and its scan. */
    public void delete(String token, UUID submissionId) {
        store.delete(reservationOf(token).getId(), submissionId);
    }

    private Reservation reservationOf(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw HotelErrors.notFound("Link not found");
        }
        return links.resolve(token);
    }

    private static void requireOpen(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw HotelErrors.checkinClosed("Online check-in is closed for this reservation");
        }
    }

    private static CheckinSummary.SubmittedGuest view(PreArrivalGuest guest) {
        return new CheckinSummary.SubmittedGuest(guest.getId(), MaskedName.of(guest.getFullName()), guest.getIdType());
    }

    /** The guest-facing name: Vietnamese first, then English, then any, then the code. */
    private static String roomTypeName(RoomType type) {
        Map<String, String> names = type.getNameI18n();
        if (names != null) {
            for (String language : List.of("vi", "en")) {
                String name = names.get(language);
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
            for (String name : names.values()) {
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return type.getCode();
    }
}
