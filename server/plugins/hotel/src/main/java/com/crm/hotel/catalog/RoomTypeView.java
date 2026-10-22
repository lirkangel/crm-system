package com.crm.hotel.catalog;

import com.crm.hotel.domain.RoomType;

import java.util.Map;
import java.util.UUID;

/** A room type as served by the API. */
public record RoomTypeView(
        UUID id,
        String code,
        Map<String, String> nameI18n,
        int maxGuests,
        long nightlyRateGrossVnd,
        boolean active,
        long version) {

    static RoomTypeView of(RoomType type) {
        return new RoomTypeView(
            type.getId(), type.getCode(), type.getNameI18n(), type.getMaxGuests(),
            type.getNightlyRateGrossVnd(), type.isActive(), type.getVersion());
    }
}
