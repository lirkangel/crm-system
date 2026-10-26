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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class StayController {

    private static final String IF_MATCH = "If-Match";

    private final CheckInService checkIns;
    private final CheckOutService checkOuts;

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

    /** Only on the business date of check-in, and only while the folio has no extra charges. */
    @PostMapping("/reservations/{id}/undo-check-in")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKIN + "')")
    public CommonResponse<ReservationView> undoCheckIn(@PathVariable("id") UUID id) {
        return CommonResponse.success(checkIns.undo(id));
    }

    /** The {@code If-Match} header is optional here so a missing one is the hotel's 428, not a generic 400. */
    @PutMapping("/reservations/{id}/stay-guests/{stayGuestId}")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKIN + "')")
    public CommonResponse<StayGuestView> correctStayGuest(
            @PathVariable("id") UUID id,
            @PathVariable("stayGuestId") UUID stayGuestId,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StayGuestRequest request) {
        return CommonResponse.success(checkIns.correct(id, stayGuestId, request, ifMatch));
    }

    /** Only a checked-in stay with a zero folio balance; early and late check-outs are both allowed. */
    @PostMapping("/reservations/{id}/check-out")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKOUT + "')")
    public CommonResponse<ReservationView> checkOut(@PathVariable("id") UUID id) {
        return CommonResponse.success(checkOuts.checkOut(id));
    }

    /** Only on the business date of the check-out, and only while the room is still free for the stay. */
    @PostMapping("/reservations/{id}/undo-check-out")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKOUT + "')")
    public CommonResponse<ReservationView> undoCheckOut(@PathVariable("id") UUID id) {
        return CommonResponse.success(checkOuts.undo(id));
    }
}
