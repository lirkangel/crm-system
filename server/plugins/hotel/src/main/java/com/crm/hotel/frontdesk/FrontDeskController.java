package com.crm.hotel.frontdesk;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import com.crm.hotel.booking.QueryDates;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class FrontDeskController {

    private final FrontDeskService frontDesk;

    /**
     * {@code date} is optional (default: the business date) and textual at the binding level so that a malformed
     * one is the hotel's 400 {@code HOTEL_INVALID_QUERY}, not a generic binding error.
     */
    @GetMapping("/front-desk/arrivals")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<List<FrontDeskEntry>> arrivals(
            @RequestParam(value = "date", required = false) String date) {
        return CommonResponse.success(frontDesk.arrivals(QueryDates.optional("date", date)));
    }

    @GetMapping("/front-desk/in-house")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<List<FrontDeskEntry>> inHouse() {
        return CommonResponse.success(frontDesk.inHouse());
    }

    @GetMapping("/front-desk/departures")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<List<FrontDeskEntry>> departures(
            @RequestParam(value = "date", required = false) String date) {
        return CommonResponse.success(frontDesk.departures(QueryDates.optional("date", date)));
    }
}
