package com.crm.hotel.guest;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class GuestController {

    private final GuestService guests;

    /** {@code q} is optional at the binding level so a missing one is the hotel's 400, not a generic one. */
    @GetMapping("/guests")
    @PreAuthorize("hasAuthority('" + HotelPermissions.GUESTS_READ + "')")
    public CommonResponse<List<GuestView>> search(@RequestParam(value = "q", required = false) String q) {
        return CommonResponse.success(guests.search(q));
    }

    @PostMapping("/guests")
    @PreAuthorize("hasAuthority('" + HotelPermissions.GUESTS_MANAGE + "')")
    public ResponseEntity<CommonResponse<GuestView>> create(@Valid @RequestBody GuestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(guests.create(request)));
    }
}
