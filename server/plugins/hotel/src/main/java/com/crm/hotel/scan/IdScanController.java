package com.crm.hotel.scan;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.HotelPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class IdScanController {

    private final IdScanService scans;

    /** 201 for a new scan, 200 when the same image was already uploaded for the reservation. */
    @PostMapping(value = "/reservations/{id}/id-scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_CHECKIN + "')")
    public ResponseEntity<CommonResponse<IdScanView>> upload(
            @PathVariable("id") UUID id, @RequestPart("image") MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw HotelErrors.scanUnreadable("The upload could not be read");
        }
        IdScanService.UploadResult result = scans.upload(id, bytes);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(result.view()));
    }

    /** The stored (downscaled) image of the scan linked to an adult's identity record: personal data, never cached. */
    @GetMapping("/reservations/{id}/stay-guests/{stayGuestId}/id-scan")
    @PreAuthorize("hasAuthority('" + HotelPermissions.STAYS_READ + "')")
    public ResponseEntity<byte[]> image(
            @PathVariable("id") UUID id, @PathVariable("stayGuestId") UUID stayGuestId) {
        StoredImage image = scans.image(id, stayGuestId)
            .orElseThrow(() -> HotelErrors.notFound("No scan for this guest"));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(CacheControl.noStore())
            .body(image.bytes());
    }
}
