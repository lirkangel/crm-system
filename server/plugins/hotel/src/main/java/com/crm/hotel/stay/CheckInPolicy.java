package com.crm.hotel.stay;

import com.crm.hotel.HotelConfig;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.ReservationStatus;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The hotel's check-in rules. Pure: the caller supplies "today" (hotel-local, see {@link #today}) so the rules are
 * testable without a clock.
 */
public final class CheckInPolicy {

    /** Vietnamese citizens must declare an address. */
    static final String VIETNAM = "VN";

    private CheckInPolicy() {
    }

    /** The hotel-local date ({@link HotelConfig#HOTEL_ZONE}) at {@code clock}'s instant, whatever its zone. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(HotelConfig.HOTEL_ZONE));
    }

    /**
     * Whether a stay of {@code [checkIn, checkOut)} may check in on {@code today}: not early, late arrival is fine,
     * and never on or after the check-out day.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_STATE} (409) unless {@code CONFIRMED}
     *         (reported first); {@code HOTEL_NOT_CHECKIN_DAY} (409) unless {@code checkIn <= today < checkOut}
     */
    public static void check(ReservationStatus status, LocalDate checkIn, LocalDate checkOut, LocalDate today) {
        if (status != ReservationStatus.CONFIRMED) {
            throw HotelErrors.invalidState("Only a confirmed reservation can check in; this one is " + status);
        }
        if (today.isBefore(checkIn) || !today.isBefore(checkOut)) {
            throw HotelErrors.notCheckInDay(
                "Check-in is possible from " + checkIn + " until the day before " + checkOut + "; today is " + today);
        }
    }

    /**
     * The rules of an identity record that span fields (single-field rules are bean validation on the request).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_ID_DOC} (400) for a Vietnamese citizen
     *         without an address or an entry date after {@code today}
     */
    public static void checkIdentity(StayGuestRequest adult, LocalDate today) {
        if (VIETNAM.equals(adult.nationality()) && (adult.address() == null || adult.address().isBlank())) {
            throw HotelErrors.invalidIdDoc("An address is required for Vietnamese citizens");
        }
        if (adult.entryDate() != null && adult.entryDate().isAfter(today)) {
            throw HotelErrors.invalidIdDoc("The entry date must not be in the future");
        }
    }
}
