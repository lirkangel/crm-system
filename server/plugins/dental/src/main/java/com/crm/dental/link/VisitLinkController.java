package com.crm.dental.link;

import com.crm.dental.DentalPermissions;
import com.crm.foundation.DTO.CommonResponse;
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

/** Staff endpoints for personal pre-visit links; served under {@code /api/v1/plugins/dental}. */
@RestController
@RequiredArgsConstructor
public class VisitLinkController {

    private final VisitLinkService links;

    /** Makes (or regenerates) the link: the URL holds the token, so the response is never cached. */
    @PostMapping("/appointments/{id}/visit-link")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public ResponseEntity<CommonResponse<VisitLinkService.CreatedLink>> create(@PathVariable("id") UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(links.create(id)));
    }

    @DeleteMapping("/appointments/{id}/visit-link")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id) {
        links.revoke(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/appointments/{id}/visit-link")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_READ + "')")
    public CommonResponse<VisitLinkView> status(@PathVariable("id") UUID id) {
        return CommonResponse.success(links.status(id));
    }
}
