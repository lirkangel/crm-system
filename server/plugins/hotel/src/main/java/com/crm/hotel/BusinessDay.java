package com.crm.hotel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The front desk's "today": the business date, i.e. the {@link HotelConfig#HOTEL_ZONE} date of (now − cutoff). With
 * the default 06:00 cutoff, 00:30 on the 11th is still the 10th, so a guest arriving after midnight checks in for the
 * night they booked. Check-in, booking, the front-desk lists and the same-day undo rules use it; dates that describe
 * the real world (an identity record's entry date) use {@link #calendarToday()}.
 */
public final class BusinessDay {

    /** Server setting of the cutoff, {@code HH:mm} (env {@code HOTEL_BUSINESS_DAY_CUTOFF}). */
    public static final String CUTOFF_PROPERTY = "hotel.business-day-cutoff";
    public static final String DEFAULT_CUTOFF = "06:00";

    private static final Pattern HH_MM = Pattern.compile("\\d{2}:\\d{2}");

    private final Clock clock;
    private final long cutoffSeconds;

    public BusinessDay(Clock clock, LocalTime cutoff) {
        this.clock = clock;
        this.cutoffSeconds = cutoff.toSecondOfDay();
    }

    /** Today's business date. */
    public LocalDate today() {
        return dateOf(Instant.now(clock));
    }

    /** The business date {@code instant} falls on (e.g. of {@code checkedInAt}, for the same-day undo rules). */
    public LocalDate dateOf(Instant instant) {
        return LocalDateTime.ofInstant(instant, HotelConfig.HOTEL_ZONE).minusSeconds(cutoffSeconds).toLocalDate();
    }

    /** The hotel-local calendar date, whatever the cutoff. */
    public LocalDate calendarToday() {
        return LocalDate.now(clock.withZone(HotelConfig.HOTEL_ZONE));
    }

    /**
     * Parses the {@value #CUTOFF_PROPERTY} setting.
     *
     * @throws IllegalArgumentException unless {@code HH:mm} between 00:00 and 23:59
     */
    public static LocalTime parseCutoff(String value) {
        String trimmed = value == null ? "" : value.trim();
        try {
            if (HH_MM.matcher(trimmed).matches()) {
                return LocalTime.parse(trimmed);
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        throw new IllegalArgumentException(
            CUTOFF_PROPERTY + " must be a time of day HH:mm (00:00 to 23:59), not '" + value + "'");
    }
}
