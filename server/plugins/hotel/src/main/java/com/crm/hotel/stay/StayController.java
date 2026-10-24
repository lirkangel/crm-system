package com.crm.hotel.stay;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import com.crm.hotel.booking.ReservationView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class StayController {

    private final CheckInService checkIns;

    @PostMapping("/reservations/{id}/check-in")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKIN + "')")
    public CommonResponse<ReservationView> checkIn(
            @PathVariable("id") UUID id, @Valid @RequestBody CheckInRequest request) {
        return CommonResponse.success(checkIns.checkIn(id, request));
    }

    /** Identity records are personal data, hence their own permission. */
    @GetMapping("/reservations/{id}/stay-guests")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_READ + "')")
    public CommonResponse<List<StayGuestView>> stayGuests(@PathVariable("id") UUID id) {
        return CommonResponse.success(checkIns.stayGuests(id));
    }
}
