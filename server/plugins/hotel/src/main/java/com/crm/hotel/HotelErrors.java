package com.crm.hotel;

import com.crm.foundation.Plugin.PluginProblem;

/**
 * Stable error codes of the hotel API and the problems carrying them (rendered
 * by Foundation as problem+json with the code as a property).
 */
public final class HotelErrors {

    public static final String INVALID_STAY = "HOTEL_INVALID_STAY";
    public static final String TOO_MANY_GUESTS = "HOTEL_TOO_MANY_GUESTS";
    public static final String ROOM_NOT_BOOKABLE = "HOTEL_ROOM_NOT_BOOKABLE";
    public static final String ROOM_UNAVAILABLE = "HOTEL_ROOM_UNAVAILABLE";
    public static final String NOT_FOUND = "HOTEL_NOT_FOUND";
    public static final String ALREADY_CANCELLED = "HOTEL_ALREADY_CANCELLED";
    public static final String DUPLICATE = "HOTEL_DUPLICATE";
    public static final String VERSION_REQUIRED = "HOTEL_VERSION_REQUIRED";
    public static final String INVALID_QUERY = "HOTEL_INVALID_QUERY";
    public static final String INVALID_GUEST = "HOTEL_INVALID_GUEST";

    private HotelErrors() {
    }

    /** 400: check-out not after check-in, stay length or check-in date out of range. */
    public static PluginProblem invalidStay(String detail) {
        return new PluginProblem(400, INVALID_STAY, detail);
    }

    /** 400: adults outside 1..maxGuests of the room type. */
    public static PluginProblem tooManyGuests(String detail) {
        return new PluginProblem(400, TOO_MANY_GUESTS, detail);
    }

    /** 409: the room is out of service or its room type inactive. */
    public static PluginProblem roomNotBookable(String detail) {
        return new PluginProblem(409, ROOM_NOT_BOOKABLE, detail);
    }

    /** 409: the room already has a confirmed reservation overlapping the stay. */
    public static PluginProblem roomUnavailable(String detail) {
        return new PluginProblem(409, ROOM_UNAVAILABLE, detail);
    }

    /** 404: no such room type, room, guest or reservation. */
    public static PluginProblem notFound(String detail) {
        return new PluginProblem(404, NOT_FOUND, detail);
    }

    /** 409: the reservation is already cancelled. */
    public static PluginProblem alreadyCancelled(String detail) {
        return new PluginProblem(409, ALREADY_CANCELLED, detail);
    }

    /** 409: a unique business key (room type code, room number) is already taken. */
    public static PluginProblem duplicate(String detail) {
        return new PluginProblem(409, DUPLICATE, detail);
    }

    /** 428: an update arrived without the {@code If-Match} version. */
    public static PluginProblem versionRequired(String detail) {
        return new PluginProblem(428, VERSION_REQUIRED, detail);
    }

    /** 400: malformed query parameters (dates, search text). */
    public static PluginProblem invalidQuery(String detail) {
        return new PluginProblem(400, INVALID_QUERY, detail);
    }

    /** 400: guest data failed the hotel's rules (name, phone, email). */
    public static PluginProblem invalidGuest(String detail) {
        return new PluginProblem(400, INVALID_GUEST, detail);
    }
}
