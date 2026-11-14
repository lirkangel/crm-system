package com.crm.hotel.checkin;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.Plugin.PublicEndpoint;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.scan.IdScanService;
import com.crm.hotel.scan.IdScanView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * The guest's online check-in, served under {@code /api/v1/plugins/hotel/public/checkin}: no login, the personal link's
 * token in the {@code X-Checkin-Token} header (never in the path or query). Foundation adds {@code Cache-Control:
 * no-store} and {@code X-Robots-Tag: noindex} to every response here, errors included.
 */
@RestController
@RequiredArgsConstructor
public class PublicCheckinController {

    private static final String TOKEN_HEADER = "X-Checkin-Token";

    private final PreArrivalService checkin;

    @GetMapping("/public/checkin")
    @PublicEndpoint
    public CommonResponse<CheckinSummary> summary(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return CommonResponse.success(checkin.summary(token));
    }

    /** 201 for a new scan, 200 when the same image was already uploaded for the reservation. */
    @PostMapping(value = "/public/checkin/id-scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PublicEndpoint
    public ResponseEntity<CommonResponse<IdScanView>> upload(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestPart("image") MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw HotelErrors.scanUnreadable("The upload could not be read");
        }
        IdScanService.UploadResult result = checkin.upload(token, bytes);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(result.view()));
    }

    /** 201 for a new submission, 200 with the stored one when this document was already submitted. */
    @PostMapping("/public/checkin/guests")
    @PublicEndpoint
    public ResponseEntity<CommonResponse<CheckinSummary.SubmittedGuest>> submit(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody PreArrivalRequest request) {
        PreArrivalService.SubmitResult result = checkin.submit(token, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(result.guest()));
    }

    @DeleteMapping("/public/checkin/guests/{id}")
    @PublicEndpoint
    public ResponseEntity<Void> delete(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token, @PathVariable("id") UUID id) {
        checkin.delete(token, id);
        return ResponseEntity.noContent().build();
    }
}
