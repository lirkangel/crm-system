package com.crm.hotel.booking;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import com.crm.hotel.BusinessDay;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.BookingSource;
import com.crm.hotel.domain.Guest;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.domain.Room;
import com.crm.hotel.domain.RoomType;
import com.crm.hotel.guest.GuestService;
import com.crm.hotel.guest.GuestView;
import com.crm.hotel.repo.FolioPaymentRepository;
import com.crm.hotel.repo.GuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import com.crm.hotel.repo.RoomRepository;
import com.crm.hotel.repo.RoomTypeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Booking, viewing, listing and cancelling reservations. No double booking: the database's
 * {@code reservation_no_overlap} exclusion constraint (confirmed and checked-in stays) is the guarantee; the room lock and the
 * overlap pre-check only make the common conflict a friendly 409 instead of a constraint error.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    static final String ENTITY_TYPE = "Reservation";
    static final String AUDIT_CREATE = "RESERVATION_CREATE";
    static final String AUDIT_CANCEL = "RESERVATION_CANCEL";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final BusinessDay businessDay;
    private final ReservationRepository reservations;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final GuestRepository guests;
    private final GuestService guestService;
    private final FolioPaymentRepository folioPayments;
    private final ReservationCodeGenerator codes;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    /**
     * Books a room in one transaction: an inline new guest is created only if the booking succeeds.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_GUEST} (400) unless exactly one of
     *         guestId/newGuest is given; {@code HOTEL_INVALID_SOURCE} (400) for an OTA booking without a channel or a
     *         channel on a non-OTA booking; {@code HOTEL_NOT_FOUND} (404) for an unknown guest or room;
     *         {@code HOTEL_INVALID_STAY}/{@code HOTEL_TOO_MANY_GUESTS} (400) from {@link StayPolicy};
     *         {@code HOTEL_ROOM_NOT_BOOKABLE} (409) for a room out of service or of an inactive type;
     *         {@code HOTEL_ROOM_UNAVAILABLE} (409) when a confirmed or checked-in stay of the room overlaps
     */
    @Transactional
    public ReservationView create(CreateReservationRequest request) {
        if ((request.guestId() == null) == (request.newGuest() == null)) {
            throw HotelErrors.invalidGuest("Give exactly one of guestId (an existing guest) or newGuest");
        }
        BookingSource source = request.source() == null ? BookingSource.WALK_IN : request.source();
        if ((source == BookingSource.OTA) != (request.otaChannel() != null)) {
            throw HotelErrors.invalidSource(source == BookingSource.OTA
                ? "An OTA booking needs the otaChannel it came through"
                : "otaChannel is only for OTA bookings");
        }
        Guest existingGuest = request.guestId() == null ? null : guests.findById(request.guestId())
            .orElseThrow(() -> HotelErrors.notFound("Guest not found"));

        // Concurrent bookings of this room wait here until this transaction ends, then see its result.
        Room room = rooms.lockById(request.roomId()).orElseThrow(() -> HotelErrors.notFound("Room not found"));
        RoomType type = roomTypes.findById(room.getRoomTypeId())
            .orElseThrow(() -> HotelErrors.notFound("Room type not found"));
        LocalDate checkIn = request.checkIn();
        LocalDate checkOut = request.checkOut();
        StayPolicy.check(checkIn, checkOut, request.adults(), type.getMaxGuests(), businessDay.today());
        if (!room.isInService() || !type.isActive()) {
            throw HotelErrors.roomNotBookable("The room is out of service or its room type is inactive");
        }
        if (reservations.countBlockingOverlaps(room.getId(), checkIn, checkOut) > 0) {
            throw HotelErrors.roomUnavailable(OverlapGuard.UNAVAILABLE);
        }
        long totalGross = StayPolicy.totalGross(type.getNightlyRateGrossVnd(), StayPolicy.nights(checkIn, checkOut));

        UUID guestId;
        String guestName;
        if (existingGuest != null) {
            guestId = existingGuest.getId();
            guestName = existingGuest.getFullName();
        } else {
            GuestView created = guestService.create(request.newGuest());
            guestId = created.id();
            guestName = created.fullName();
        }

        Reservation reservation = new Reservation();
        reservation.setCode(codes.next());
        reservation.setGuestId(guestId);
        reservation.setRoomId(room.getId());
        reservation.setCheckIn(checkIn);
        reservation.setCheckOut(checkOut);
        reservation.setAdults(request.adults());
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setNightlyRateGrossVnd(type.getNightlyRateGrossVnd());
        reservation.setTotalGrossVnd(totalGross);
        reservation.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
        reservation.setSource(source);
        reservation.setOtaChannel(request.otaChannel());
        reservation.setOtaReference(request.otaReference() == null || request.otaReference().isBlank()
            ? null : request.otaReference().trim());
        reservation.setCreatedBy(currentUser.id().orElse(null));
        reservation.setCreatedAt(now());
        Reservation saved = OverlapGuard.saveAndFlush(reservations, reservation);

        changes.record(ENTITY_TYPE, saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        ReservationView view = ReservationView.of(saved, guestName, room.getNumber());
        audit.record(ENTITY_TYPE, saved.getId(), AUDIT_CREATE, null, json(view));
        return view;
    }

    @Transactional(readOnly = true)
    public ReservationView get(UUID id) {
        return reservations.findDetailed(id).stream().findFirst().map(ReservationView::of)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
    }

    /** Reservations of any status overlapping {@code [from, to)}, by check-in. */
    @Transactional(readOnly = true)
    public List<ReservationView> list(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            throw HotelErrors.invalidQuery("to must be after from");
        }
        return reservations.findDetailedOverlapping(from, to).stream().map(ReservationView::of).toList();
    }

    /**
     * {@code CONFIRMED -> CANCELLED}; frees the room at once (the exclusion constraint ignores cancelled
     * stays). The row is locked first, so of two concurrent cancels the second sees the first's result.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404),
     *         {@code HOTEL_ALREADY_CANCELLED} (409) when it is already cancelled, {@code HOTEL_INVALID_STATE}
     *         (409) when it is checked in or out (a stay that has begun can't be cancelled), or
     *         {@code HOTEL_PAYMENTS_ON_FOLIO} (409) while the folio has an active payment
     */
    @Transactional
    public ReservationView cancel(UUID id) {
        Reservation reservation = reservations.lockById(id)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            throw HotelErrors.alreadyCancelled("The reservation is already cancelled");
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw HotelErrors.invalidState("Only a confirmed reservation can be cancelled; this one is "
                + reservation.getStatus());
        }
        // Refunds happen outside the system: staff void the payments first. The reservation lock above serializes
        // this check against folio payments being added.
        if (folioPayments.existsByReservationIdAndVoidedAtIsNull(id)) {
            throw HotelErrors.paymentsOnFolio("The folio has payments; void them before cancelling");
        }
        String guestName = guests.findById(reservation.getGuestId()).map(Guest::getFullName).orElse(null);
        String roomNumber = rooms.findById(reservation.getRoomId()).map(Room::getNumber).orElse(null);
        String before = json(ReservationView.of(reservation, guestName, roomNumber));

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(now());
        Reservation saved = reservations.saveAndFlush(reservation);

        changes.record(ENTITY_TYPE, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        ReservationView view = ReservationView.of(saved, guestName, roomNumber);
        audit.record(ENTITY_TYPE, saved.getId(), AUDIT_CANCEL, before, json(view));
        return view;
    }

    /** Microsecond precision, as stored by {@code timestamptz}, so responses and later reads agree. */
    private Instant now() {
        return Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS);
    }

    private String json(ReservationView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reservation " + view.id() + " could not be serialized for the audit log", e);
        }
    }
}
