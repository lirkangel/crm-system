package com.crm.hotel.catalog;

import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.IfMatch;
import com.crm.hotel.domain.Room;
import com.crm.hotel.domain.RoomType;
import com.crm.hotel.repo.RoomRepository;
import com.crm.hotel.repo.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Room types and rooms: reads, and writes that publish their new version to the change feed. */
@Service
@RequiredArgsConstructor
public class RoomCatalogService {

    private static final String UNIQUE_VIOLATION = "23505";

    private final RoomTypeRepository roomTypes;
    private final RoomRepository rooms;
    private final ChangeRecorder changes;

    @Transactional(readOnly = true)
    public List<RoomTypeView> listRoomTypes() {
        return roomTypes.findAll(Sort.by("code")).stream().map(RoomTypeView::of).toList();
    }

    @Transactional
    public RoomTypeView createRoomType(RoomTypeRequest request) {
        RoomType type = new RoomType();
        apply(type, request);
        RoomType saved = flushUnique(() -> roomTypes.saveAndFlush(type), "room type code " + request.code());
        changes.record("RoomType", saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        return RoomTypeView.of(saved);
    }

    @Transactional
    public RoomTypeView updateRoomType(UUID id, RoomTypeRequest request, String ifMatch) {
        RoomType type = roomTypes.findById(id).orElseThrow(() -> HotelErrors.notFound("Room type not found"));
        IfMatch.requireVersion(ifMatch, type.getVersion(), RoomType.class, id);
        apply(type, request);
        RoomType saved = flushUnique(() -> roomTypes.saveAndFlush(type), "room type code " + request.code());
        changes.record("RoomType", saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        return RoomTypeView.of(saved);
    }

    @Transactional(readOnly = true)
    public List<RoomView> listRooms() {
        return rooms.findAll(Sort.by("number")).stream().map(RoomView::of).toList();
    }

    @Transactional
    public RoomView createRoom(RoomRequest request) {
        requireRoomType(request.roomTypeId());
        Room room = new Room();
        apply(room, request);
        Room saved = flushUnique(() -> rooms.saveAndFlush(room), "room number " + request.number());
        changes.record("Room", saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        return RoomView.of(saved);
    }

    @Transactional
    public RoomView updateRoom(UUID id, RoomRequest request, String ifMatch) {
        Room room = rooms.findById(id).orElseThrow(() -> HotelErrors.notFound("Room not found"));
        IfMatch.requireVersion(ifMatch, room.getVersion(), Room.class, id);
        requireRoomType(request.roomTypeId());
        apply(room, request);
        Room saved = flushUnique(() -> rooms.saveAndFlush(room), "room number " + request.number());
        changes.record("Room", saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        return RoomView.of(saved);
    }

    private static void apply(RoomType type, RoomTypeRequest request) {
        type.setCode(request.code().trim());
        type.setNameI18n(new LinkedHashMap<>(request.nameI18n()));
        type.setMaxGuests(request.maxGuests());
        type.setNightlyRateGrossVnd(request.nightlyRateGrossVnd());
        type.setActive(request.active());
    }

    private static void apply(Room room, RoomRequest request) {
        room.setNumber(request.number().trim());
        room.setRoomTypeId(request.roomTypeId());
        room.setFloor(request.floor());
        room.setInService(request.inService());
    }

    private void requireRoomType(UUID roomTypeId) {
        if (!roomTypes.existsById(roomTypeId)) {
            throw HotelErrors.notFound("Room type not found");
        }
    }

    /** Runs a flushing save; a unique-key violation becomes 409 {@code HOTEL_DUPLICATE}. */
    private static <T> T flushUnique(Supplier<T> save, String what) {
        try {
            return save.get();
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw HotelErrors.duplicate("A record with this " + what + " already exists");
            }
            throw e;
        }
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
