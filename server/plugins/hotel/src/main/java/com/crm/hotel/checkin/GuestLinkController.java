package com.crm.hotel.checkin;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Staff endpoints for personal check-in links; served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class GuestLinkController {

    private final GuestLinkService links;

    /** Makes (or regenerates) the link: the URL holds the token, so the response is never cached. */
    @PostMapping("/reservations/{id}/guest-link")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKIN + "')")
    public ResponseEntity<CommonResponse<GuestLinkService.CreatedLink>> create(@PathVariable("id") UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(links.create(id)));
    }

    @DeleteMapping("/reservations/{id}/guest-link")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKIN + "')")
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id) {
        links.revoke(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reservations/{id}/guest-link")
    @PreAuthorize("hasAuthority('" + HotelPermissions.RESERVATIONS_READ + "')")
    public CommonResponse<GuestLinkView> status(@PathVariable("id") UUID id) {
        return CommonResponse.success(links.status(id));
    }
}
