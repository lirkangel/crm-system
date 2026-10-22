package com.crm.hotel.booking;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservations;

    /** Booking with an inline new guest needs only this permission, not {@code hotel.guests.manage}. */
    @PostMapping("/reservations")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_CREATE + "')")
    public ResponseEntity<CommonResponse<ReservationView>> create(@Valid @RequestBody CreateReservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(reservations.create(request)));
    }

    @GetMapping("/reservations/{id}")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<ReservationView> get(@PathVariable("id") UUID id) {
        return CommonResponse.success(reservations.get(id));
    }

    /** Reservations overlapping {@code [from, to)}; both dates are required (400 {@code HOTEL_INVALID_QUERY}). */
    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<List<ReservationView>> list(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return CommonResponse.success(
            reservations.list(QueryDates.required("from", from), QueryDates.required("to", to)));
    }

    @PostMapping("/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_CANCEL + "')")
    public CommonResponse<ReservationView> cancel(@PathVariable("id") UUID id) {
        return CommonResponse.success(reservations.cancel(id));
    }
}
