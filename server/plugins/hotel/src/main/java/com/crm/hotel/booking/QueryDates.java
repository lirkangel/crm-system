package com.crm.hotel.booking;

import com.crm.hotel.HotelErrors;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Parses date query parameters bound as optional strings, so that a missing or malformed one is
 * the hotel's 400 {@code HOTEL_INVALID_QUERY}, not a generic binding error.
 */
public final class QueryDates {

    private QueryDates() {
    }

    public static LocalDate required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw HotelErrors.invalidQuery(name + " is required (ISO date, yyyy-MM-dd)");
        }
        return parse(name, value);
    }

    /** {@code null} when the parameter is missing or blank (the caller's default applies); 400 when malformed. */
    public static LocalDate optional(String name, String value) {
        return value == null || value.isBlank() ? null : parse(name, value);
    }

    private static LocalDate parse(String name, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw HotelErrors.invalidQuery(name + " must be an ISO date (yyyy-MM-dd)");
        }
    }
}
