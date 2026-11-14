package com.crm.hotel.checkin;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import com.crm.hotel.scan.StoredImage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staff view of what guests sent through their links; served under {@code /api/v1/plugins/hotel}. Personal data, so
 * both responses are {@code no-store}.
 */
@RestController
@RequiredArgsConstructor
public class PreArrivalController {

    private final PreArrivalService preArrivals;

    @GetMapping("/reservations/{id}/pre-arrival-guests")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_READ + "')")
    public ResponseEntity<CommonResponse<List<PreArrivalGuestView>>> list(@PathVariable("id") UUID id) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(preArrivals.staffList(id)));
    }

    /** The stored (downscaled) CCCD photo of a submission. */
    @GetMapping("/reservations/{id}/pre-arrival-guests/{guestId}/id-scan")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_READ + "')")
    public ResponseEntity<byte[]> image(@PathVariable("id") UUID id, @PathVariable("guestId") UUID guestId) {
        StoredImage image = preArrivals.staffImage(id, guestId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(CacheControl.noStore())
            .body(image.bytes());
    }
}
