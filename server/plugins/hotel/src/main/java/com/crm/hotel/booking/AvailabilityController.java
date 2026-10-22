package com.crm.hotel.booking;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.HotelPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availability;

    /**
     * The parameters are optional and textual at the binding level so that a missing or malformed
     * one is the hotel's 400 {@code HOTEL_INVALID_QUERY}, not a generic binding error.
     */
    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<AvailabilityView> search(
            @RequestParam(value = "checkIn", required = false) String checkIn,
            @RequestParam(value = "checkOut", required = false) String checkOut,
            @RequestParam(value = "adults", required = false) String adults) {
        return CommonResponse.success(availability.search(
            date("checkIn", checkIn), date("checkOut", checkOut), adults(adults)));
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            throw HotelErrors.invalidQuery(name + " is required (ISO date, yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw HotelErrors.invalidQuery(name + " must be an ISO date (yyyy-MM-dd)");
        }
    }

    private static int adults(String value) {
        if (value == null || value.isBlank()) {
            throw HotelErrors.invalidQuery("adults is required");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw HotelErrors.invalidQuery("adults must be a whole number");
        }
    }
}
