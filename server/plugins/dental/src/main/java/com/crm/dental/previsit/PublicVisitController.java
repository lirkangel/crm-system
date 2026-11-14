package com.crm.dental.previsit;

import com.crm.dental.DentalErrors;
import com.crm.dental.scan.DentalIdScanService;
import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.Plugin.PublicEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The patient's pre-visit form, served under {@code /api/v1/plugins/dental/public/visit}: no login, the personal
 * link's token in the {@code X-Visit-Token} header (never in the path or query). Foundation adds {@code Cache-Control:
 * no-store} and {@code X-Robots-Tag: noindex} to every response here, errors included. The body is not
 * bean-validated: {@link PrevisitRules} answers every failure as {@code DENTAL_INVALID_FORM} without echoing values.
 */
@RestController
@RequiredArgsConstructor
public class PublicVisitController {

    private static final String TOKEN_HEADER = "X-Visit-Token";

    private final PrevisitService previsit;

    @GetMapping("/public/visit")
    @PublicEndpoint
    public CommonResponse<VisitSummary> summary(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return CommonResponse.success(previsit.summary(token));
    }

    /** 201 for a new scan, 200 when the same image was already uploaded for the appointment. */
    @PostMapping(value = "/public/visit/id-scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PublicEndpoint
    public ResponseEntity<CommonResponse<ScanView>> upload(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestPart("image") MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw DentalErrors.scanUnreadable("The upload could not be read");
        }
        DentalIdScanService.UploadResult result = previsit.upload(token, bytes);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(new ScanView(result.scanId(), result.fields())));
    }

    /** 201 when the form is created, 200 when it replaces this appointment's earlier submission. */
    @PostMapping("/public/visit/form")
    @PublicEndpoint
    public ResponseEntity<Void> submit(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody PrevisitRequest request) {
        PrevisitService.SubmitResult result = previsit.submit(token, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .build();
    }

    @PostMapping("/public/visit/previous")
    @PublicEndpoint
    public ResponseEntity<CommonResponse<PreviousAnswers>> previous(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody PreviousRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(previsit.previous(token, request)));
    }
}
