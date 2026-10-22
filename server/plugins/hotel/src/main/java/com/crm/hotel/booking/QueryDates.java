package com.crm.hotel.booking;

import com.crm.hotel.HotelErrors;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Parses date query parameters bound as optional strings, so that a missing or malformed one is
 * the hotel's 400 {@code HOTEL_INVALID_QUERY}, not a generic binding error.
 */
final class QueryDates {

    private QueryDates() {
    }

    static LocalDate required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw HotelErrors.invalidQuery(name + " is required (ISO date, yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw HotelErrors.invalidQuery(name + " must be an ISO date (yyyy-MM-dd)");
        }
    }
}
