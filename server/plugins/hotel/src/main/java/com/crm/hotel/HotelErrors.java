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
    public static final String ROOM_OCCUPIED = "HOTEL_ROOM_OCCUPIED";
    public static final String PAYMENTS_ON_FOLIO = "HOTEL_PAYMENTS_ON_FOLIO";
    public static final String INVALID_STATE = "HOTEL_INVALID_STATE";
    public static final String NOT_CHECKIN_DAY = "HOTEL_NOT_CHECKIN_DAY";
    public static final String BALANCE_DUE = "HOTEL_BALANCE_DUE";
    public static final String OVERPAYMENT = "HOTEL_OVERPAYMENT";
    public static final String INVALID_ID_DOC = "HOTEL_INVALID_ID_DOC";
    public static final String INVALID_PAYMENT = "HOTEL_INVALID_PAYMENT";
    public static final String INVALID_SOURCE = "HOTEL_INVALID_SOURCE";
    public static final String SCAN_UNREADABLE = "HOTEL_SCAN_UNREADABLE";
    public static final String SCAN_UNSUPPORTED = "HOTEL_SCAN_UNSUPPORTED";
    public static final String SCAN_MISMATCH = "HOTEL_SCAN_MISMATCH";
    public static final String SCAN_LIMIT = "HOTEL_SCAN_LIMIT";
    public static final String SCAN_BUSY = "HOTEL_SCAN_BUSY";

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

    /** 409: the room already has a confirmed or checked-in reservation overlapping the stay. */
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

    /** 409: another stay in the room is checked in (an overstayer), so it can't be given to someone else. */
    public static PluginProblem roomOccupied(String detail) {
        return new PluginProblem(409, ROOM_OCCUPIED, detail);
    }

    /** 409: the reservation still has active payments, so it can't be cancelled. */
    public static PluginProblem paymentsOnFolio(String detail) {
        return new PluginProblem(409, PAYMENTS_ON_FOLIO, detail);
    }

    /** 409: the reservation's status doesn't allow the requested transition or folio change. */
    public static PluginProblem invalidState(String detail) {
        return new PluginProblem(409, INVALID_STATE, detail);
    }

    /** 409: check-in while the business date is outside {@code [check_in, check_out)}. */
    public static PluginProblem notCheckInDay(String detail) {
        return new PluginProblem(409, NOT_CHECKIN_DAY, detail);
    }

    /** 409: check-out refused because the folio balance is not zero. */
    public static PluginProblem balanceDue(String detail) {
        return new PluginProblem(409, BALANCE_DUE, detail);
    }

    /** 409: the payment would take the folio balance below zero. */
    public static PluginProblem overpayment(String detail) {
        return new PluginProblem(409, OVERPAYMENT, detail);
    }

    /** 400: an identity record failed validation (blank number or name, birth date, nationality, address). */
    public static PluginProblem invalidIdDoc(String detail) {
        return new PluginProblem(400, INVALID_ID_DOC, detail);
    }

    /** 400: malformed payment or charge (amount, method, OTA channel). */
    public static PluginProblem invalidPayment(String detail) {
        return new PluginProblem(400, INVALID_PAYMENT, detail);
    }

    /** 400: booking source OTA without a channel, or a channel on a non-OTA booking. */
    public static PluginProblem invalidSource(String detail) {
        return new PluginProblem(400, INVALID_SOURCE, detail);
    }

    /** 400: the upload is not a readable image, or its QR code is missing or not a CCCD's. */
    public static PluginProblem scanUnreadable(String detail) {
        return new PluginProblem(400, SCAN_UNREADABLE, detail);
    }

    /** 415: the upload is neither JPEG nor PNG. */
    public static PluginProblem scanUnsupportedType(String detail) {
        return new PluginProblem(415, SCAN_UNSUPPORTED, detail);
    }

    /** 413: the upload is over 10 MB (same code as the type error, as the client's remedy is the same: retake). */
    public static PluginProblem scanTooLarge(String detail) {
        return new PluginProblem(413, SCAN_UNSUPPORTED, detail);
    }

    /** 409: the scan does not match the identity record it is linked to. */
    public static PluginProblem scanMismatch(String detail) {
        return new PluginProblem(409, SCAN_MISMATCH, detail);
    }

    /** 409: the reservation already holds the maximum number of scans. */
    public static PluginProblem scanLimit(String detail) {
        return new PluginProblem(409, SCAN_LIMIT, detail);
    }

    /** 503: too many QR decodes are running; retry shortly. */
    public static PluginProblem scanBusy(String detail) {
        return new PluginProblem(503, SCAN_BUSY, detail);
    }
}
