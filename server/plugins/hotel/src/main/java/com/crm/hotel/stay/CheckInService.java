package com.crm.hotel.stay;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.booking.OverlapGuard;
import com.crm.hotel.booking.ReservationView;
import com.crm.hotel.domain.Guest;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.domain.Room;
import com.crm.hotel.domain.RoomType;
import com.crm.hotel.repo.GuestRepository;
import com.crm.hotel.repo.ReservationRepository;
import com.crm.hotel.repo.RoomRepository;
import com.crm.hotel.repo.RoomTypeRepository;
import com.crm.hotel.repo.StayGuestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Check-in: records the adults' identity records and moves a confirmed stay to {@code CHECKED_IN}, optionally
 * swapping it to another room of the same type. Locks the reservation row first, then the room rows in id order.
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    static final String RESERVATION = "Reservation";
    static final String STAY_GUEST = "StayGuest";
    static final String AUDIT_CHECK_IN = "RESERVATION_CHECK_IN";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final ReservationRepository reservations;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final GuestRepository guests;
    private final StayGuestRepository stayGuests;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    /**
     * Checks a reservation in, in one transaction.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_ID_DOC} (400) from
     *         {@link CheckInPolicy#checkIdentity}; {@code HOTEL_NOT_FOUND} (404) for an unknown reservation or swap
     *         room; {@code HOTEL_INVALID_STATE}/{@code HOTEL_NOT_CHECKIN_DAY} (409) from {@link CheckInPolicy#check};
     *         {@code HOTEL_TOO_MANY_GUESTS} (400) for more adults than the room type sleeps;
     *         {@code HOTEL_ROOM_NOT_BOOKABLE} (409) for a swap to a room of another type, out of service or of an
     *         inactive type; {@code HOTEL_ROOM_OCCUPIED} (409) when another stay is checked in to the room;
     *         {@code HOTEL_ROOM_UNAVAILABLE} (409) when the swap room is booked for some night of the stay
     */
    @Transactional
    public ReservationView checkIn(UUID reservationId, CheckInRequest request) {
        LocalDate today = CheckInPolicy.today(hotelClock);
        request.adults().forEach(adult -> CheckInPolicy.checkIdentity(adult, today));

        // Lock order: the reservation row, then room rows by id (a swap touches two rooms).
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        CheckInPolicy.check(reservation.getStatus(), reservation.getCheckIn(), reservation.getCheckOut(), today);
        UUID bookedRoomId = reservation.getRoomId();
        UUID targetRoomId = request.roomId() == null ? bookedRoomId : request.roomId();
        Map<UUID, Room> locked = lockRooms(bookedRoomId, targetRoomId);
        Room booked = locked.get(bookedRoomId);
        Room target = locked.get(targetRoomId);
        RoomType type = roomTypes.findById(booked.getRoomTypeId())
            .orElseThrow(() -> HotelErrors.notFound("Room type not found"));
        if (request.adults().size() > type.getMaxGuests()) {
            throw HotelErrors.tooManyGuests("At most " + type.getMaxGuests() + " adults for this room type");
        }
        boolean swap = !targetRoomId.equals(bookedRoomId);
        if (swap && (!target.getRoomTypeId().equals(type.getId()) || !target.isInService() || !type.isActive())) {
            throw HotelErrors.roomNotBookable("A stay only moves to an in-service room of the same, active room type");
        }
        if (reservations.existsByRoomIdAndStatusAndIdNot(targetRoomId, ReservationStatus.CHECKED_IN, reservationId)) {
            throw HotelErrors.roomOccupied("Another stay is still checked in to room " + target.getNumber());
        }

        String guestName = guests.findById(reservation.getGuestId()).map(Guest::getFullName).orElse(null);
        String before = json(ReservationView.of(reservation, guestName, booked.getNumber()));
        reservation.setRoomId(targetRoomId);
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setCheckedInAt(now());
        reservation.setAdults(request.adults().size());
        // A swap into a room booked for a later night of the stay surfaces here as HOTEL_ROOM_UNAVAILABLE.
        Reservation saved = OverlapGuard.saveAndFlush(reservations, reservation);
        changes.record(RESERVATION, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);

        List<StayGuest> records = new ArrayList<>();
        for (StayGuestRequest adult : request.adults()) {
            StayGuest record = new StayGuest();
            record.setReservationId(saved.getId());
            record.apply(adult);
            records.add(record);
        }
        List<StayGuest> savedRecords = stayGuests.saveAllAndFlush(records);
        savedRecords.forEach(
            record -> changes.record(STAY_GUEST, record.getId(), record.getVersion(), ChangeOp.CREATE));

        ReservationView view = ReservationView.of(saved, guestName, target.getNumber());
        // Only the count and ids of the identity records: the audit chain is append-only, so no personal data.
        ObjectNode after = objectMapper.valueToTree(view);
        after.put("adults", savedRecords.size());
        ArrayNode ids = after.putArray("stayGuestIds");
        savedRecords.forEach(record -> ids.add(record.getId().toString()));
        audit.record(RESERVATION, saved.getId(), AUDIT_CHECK_IN, before, json(after));
        return view;
    }

    /** Identity records of a stay (personal data: {@code hotel.stays.read}). */
    @Transactional(readOnly = true)
    public List<StayGuestView> stayGuests(UUID reservationId) {
        if (!reservations.existsById(reservationId)) {
            throw HotelErrors.notFound("Reservation not found");
        }
        return stayGuests.findByReservationIdOrderByFullNameAscIdAsc(reservationId).stream()
            .map(StayGuestView::of).toList();
    }

    /** Locks the booked and the target room ({@code FOR NO KEY UPDATE}) in id order, so swaps can't deadlock. */
    private Map<UUID, Room> lockRooms(UUID bookedRoomId, UUID targetRoomId) {
        List<UUID> ids = bookedRoomId.equals(targetRoomId)
            ? List.of(bookedRoomId)
            : List.of(bookedRoomId, targetRoomId).stream().sorted().toList();
        Map<UUID, Room> locked = new HashMap<>();
        for (UUID id : ids) {
            locked.put(id, rooms.lockById(id).orElseThrow(() -> HotelErrors.notFound("Room not found")));
        }
        return locked;
    }

    /** Microsecond precision, as stored by {@code timestamptz}, so responses and later reads agree. */
    private Instant now() {
        return Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Check-in could not be serialized for the audit log", e);
        }
    }
}
