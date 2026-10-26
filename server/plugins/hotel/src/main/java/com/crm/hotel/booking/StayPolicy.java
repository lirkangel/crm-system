package com.crm.hotel.booking;

import com.crm.hotel.HotelErrors;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The hotel's stay rules and gross pricing. Pure: the caller supplies "today"
 * (the business date, see {@link com.crm.hotel.BusinessDay}) so the rules are testable without a clock.
 */
public final class StayPolicy {

    public static final int MIN_NIGHTS = 1;
    public static final int MAX_NIGHTS = 60;

    private StayPolicy() {
    }

    /** Nights of the stay {@code [in, out)}; back-to-back stays (out of one = in of the next) share no night. */
    public static int nights(LocalDate in, LocalDate out) {
        return Math.toIntExact(ChronoUnit.DAYS.between(in, out));
    }

    /**
     * Validates a stay request.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_STAY} (400) when a date is missing,
     *         check-out is not after check-in, the stay is longer than {@value #MAX_NIGHTS} nights or check-in
     *         is before {@code today}; {@code HOTEL_TOO_MANY_GUESTS} (400) when {@code adults} is outside
     *         {@code 1..maxGuests}. Date problems are reported before capacity problems.
     */
    public static void check(LocalDate in, LocalDate out, int adults, int maxGuests, LocalDate today) {
        if (in == null || out == null) {
            throw HotelErrors.invalidStay("Check-in and check-out dates are required");
        }
        // long: the distance of two arbitrary dates does not fit an int
        long nights = ChronoUnit.DAYS.between(in, out);
        if (nights < MIN_NIGHTS) {
            throw HotelErrors.invalidStay("Check-out must be after check-in");
        }
        if (nights > MAX_NIGHTS) {
            throw HotelErrors.invalidStay("A stay is at most " + MAX_NIGHTS + " nights");
        }
        if (in.isBefore(today)) {
            throw HotelErrors.invalidStay("Check-in must not be in the past");
        }
        if (adults < 1 || adults > maxGuests) {
            throw HotelErrors.tooManyGuests(maxGuests == Integer.MAX_VALUE
                ? "Adults must be at least 1"
                : "Adults must be between 1 and " + maxGuests + " for this room type");
        }
    }

    /**
     * Gross stay total in whole VND. An overflow is never wrapped: it is reported as
     * {@code HOTEL_INVALID_STAY} (400), since only an absurd rate can cause it.
     */
    public static long totalGross(long nightlyRateGross, int nights) {
        try {
            return Math.multiplyExact(nightlyRateGross, nights);
        } catch (ArithmeticException e) {
            throw HotelErrors.invalidStay("The stay total is too large");
        }
    }
}
