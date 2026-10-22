package com.crm.hotel.catalog;

import com.crm.hotel.domain.Room;

import java.util.UUID;

/** A room as served by the API. */
public record RoomView(
        UUID id,
        String number,
        UUID roomTypeId,
        Integer floor,
        boolean inService,
        long version) {

    static RoomView of(Room room) {
        return new RoomView(
            room.getId(), room.getNumber(), room.getRoomTypeId(), room.getFloor(),
            room.isInService(), room.getVersion());
    }
}
