package com.crm.hotel.stay;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.hotel.BusinessDay;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.IfMatch;
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
import com.crm.hotel.scan.IdScanService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    static final String AUDIT_CHECK_IN_UNDO = "RESERVATION_CHECK_IN_UNDO";
    static final String AUDIT_CORRECT = "STAY_GUEST_CORRECT";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final BusinessDay businessDay;
    private final ReservationRepository reservations;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final GuestRepository guests;
    private final StayGuestRepository stayGuests;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final IdScanService scans;

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
        LocalDate calendarToday = businessDay.calendarToday();
        request.adults().forEach(adult -> CheckInPolicy.checkIdentity(adult, calendarToday));
        Set<UUID> scanIds = new HashSet<>();
        for (StayGuestRequest adult : request.adults()) {
            if (adult.idScanId() != null && !scanIds.add(adult.idScanId())) {
                throw HotelErrors.scanMismatch("One scan can only belong to one adult");
            }
        }

        // Lock order: the reservation row, then room rows by id (a swap touches two rooms).
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        CheckInPolicy.check(
            reservation.getStatus(), reservation.getCheckIn(), reservation.getCheckOut(), businessDay.today());
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
        // saveAllAndFlush keeps the order of the request
        for (int i = 0; i < savedRecords.size(); i++) {
            UUID scanId = request.adults().get(i).idScanId();
            if (scanId != null) {
                scans.link(saved.getId(), scanId, savedRecords.get(i).getId());
            }
        }

        ReservationView view = ReservationView.of(saved, guestName, target.getNumber());
        // Only the count and ids of the identity records: the audit chain is append-only, so no personal data.
        ObjectNode after = objectMapper.valueToTree(view);
        after.put("adults", savedRecords.size());
        ArrayNode ids = after.putArray("stayGuestIds");
        savedRecords.forEach(record -> ids.add(record.getId().toString()));
        if (!scanIds.isEmpty()) {
            ArrayNode linkedScans = after.putArray("idScanIds");
            scanIds.forEach(scanId -> linkedScans.add(scanId.toString()));
        }
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

    /**
     * Takes back a check-in made by mistake: only on the business date of the check-in and while the folio has no
     * extra charges (voided ones count). The identity records are deleted and the stay is {@code CONFIRMED} again;
     * payments stay on the folio. Rooms are untouched (a swap is kept, and nothing new becomes occupied), so the
     * reservation lock is enough.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless {@code CHECKED_IN}, when the check-in was on an earlier day, or when the folio has charges
     */
    @Transactional
    public ReservationView undo(UUID reservationId) {
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw HotelErrors.invalidState("Only a checked-in stay can be undone; this one is " + reservation.getStatus());
        }
        if (reservation.getCheckedInAt() == null) {
            throw HotelErrors.invalidState("Check-in can't be undone: its time was not recorded");
        }
        LocalDate checkInDay = businessDay.dateOf(reservation.getCheckedInAt());
        LocalDate today = businessDay.today();
        if (!checkInDay.equals(today)) {
            throw HotelErrors.invalidState(
                "Check-in can only be undone on the day it happened (" + checkInDay + "); today is " + today);
        }
        if (reservations.countFolioCharges(reservationId) > 0) {
            throw HotelErrors.invalidState("Check-in can't be undone: the folio already has extra charges");
        }

        String guestName = guests.findById(reservation.getGuestId()).map(Guest::getFullName).orElse(null);
        String roomNumber = rooms.findById(reservation.getRoomId()).map(Room::getNumber).orElse(null);
        String before = json(ReservationView.of(reservation, guestName, roomNumber));

        List<StayGuest> records = stayGuests.findByReservationIdOrderByFullNameAscIdAsc(reservationId);
        // The scans' FK to the stay guests has no ON DELETE: they go first
        scans.unlinkAndDelete(records.stream().map(StayGuest::getId).toList());
        stayGuests.deleteAll(records);
        stayGuests.flush();
        // Hibernate doesn't bump the version of a deleted row; the feed's DELETE carries the next one.
        records.forEach(record -> changes.record(STAY_GUEST, record.getId(), record.getVersion() + 1, ChangeOp.DELETE));

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setCheckedInAt(null);
        Reservation saved = reservations.saveAndFlush(reservation);
        changes.record(RESERVATION, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);

        ReservationView view = ReservationView.of(saved, guestName, roomNumber);
        audit.record(RESERVATION, saved.getId(), AUDIT_CHECK_IN_UNDO, before, json(view));
        return view;
    }

    /**
     * Corrects one adult's identity record of a checked-in stay (same rules as at check-in). The audit log gets the
     * names of the changed fields, never their values. Sending the record unchanged is a no-op.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_ID_DOC} (400); {@code HOTEL_NOT_FOUND}
     *         (404) for an unknown reservation, or a stay guest that isn't of this reservation;
     *         {@code HOTEL_INVALID_STATE} (409) unless {@code CHECKED_IN}; {@code HOTEL_VERSION_REQUIRED} (428)
     *         without a numeric {@code If-Match}; a stale version is an optimistic-lock failure (409 {@code CONFLICT})
     */
    @Transactional
    public StayGuestView correct(UUID reservationId, UUID stayGuestId, StayGuestRequest request, String ifMatch) {
        CheckInPolicy.checkIdentity(request, businessDay.calendarToday());
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw HotelErrors.invalidState(
                "Identity records can only be corrected while the stay is checked in; this one is "
                    + reservation.getStatus());
        }
        StayGuest record = stayGuests.findById(stayGuestId)
            .filter(found -> found.getReservationId().equals(reservationId))
            .orElseThrow(() -> HotelErrors.notFound("Stay guest not found"));
        IfMatch.requireVersion(ifMatch, record.getVersion(), StayGuest.class, stayGuestId);

        List<String> changedFields = record.changedFields(request);
        // A different scan replaces the current one (null keeps it, the same id is a no-op)
        boolean scanLinked = request.idScanId() != null
            && scans.link(reservationId, request.idScanId(), stayGuestId);
        if (changedFields.isEmpty()) {
            if (scanLinked) {
                audit.record(RESERVATION, reservationId, AUDIT_CORRECT, null,
                    json(correctionAfter(stayGuestId, changedFields, request.idScanId())));
            }
            return StayGuestView.of(record);
        }
        record.apply(request);
        StayGuest saved = stayGuests.saveAndFlush(record);
        changes.record(STAY_GUEST, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);

        // Field names only: the audit chain is append-only, so no personal data (not even the old value).
        audit.record(RESERVATION, reservationId, AUDIT_CORRECT, null,
            json(correctionAfter(saved.getId(), changedFields, scanLinked ? request.idScanId() : null)));
        return StayGuestView.of(saved);
    }

    private ObjectNode correctionAfter(UUID stayGuestId, List<String> changedFields, UUID linkedScanId) {
        ObjectNode after = objectMapper.createObjectNode();
        after.put("stayGuestId", stayGuestId.toString());
        ArrayNode fields = after.putArray("changedFields");
        changedFields.forEach(fields::add);
        if (linkedScanId != null) {
            after.put("idScanId", linkedScanId.toString());
        }
        return after;
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
