package com.crm.dental.previsit;

import com.crm.dental.DentalPermissions;
import com.crm.dental.patient.PatientView;
import com.crm.dental.scan.StoredImage;
import com.crm.foundation.DTO.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Staff side of the pre-visit form, served under {@code /api/v1/plugins/dental}. Personal data: all {@code no-store}. */
@RestController
@RequiredArgsConstructor
public class PrevisitController {

    private final PrevisitStaffService previsits;

    @GetMapping("/appointments/{id}/previsit")
    @PreAuthorize("hasAuthority('" + DentalPermissions.PREVISIT_READ + "')")
    public ResponseEntity<CommonResponse<PrevisitView>> read(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResponse.success(previsits.read(id)));
    }

    /** The stored (downscaled) CCCD photo. */
    @GetMapping("/appointments/{id}/previsit/id-scan")
    @PreAuthorize("hasAuthority('" + DentalPermissions.PREVISIT_READ + "')")
    public ResponseEntity<byte[]> idScan(@PathVariable("id") UUID id) {
        StoredImage image = previsits.image(id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(CacheControl.noStore())
            .body(image.bytes());
    }

    /**
     * Needs both permissions: it writes the patient and acts on what the previsit permission lets staff read. The
     * body binds the call to the form version staff reviewed. The {@code If-Match} header is optional here so a missing one is the clinic's 428, not a generic 400. */
    @PostMapping("/appointments/{id}/previsit/apply")
    @PreAuthorize("hasAuthority('" + DentalPermissions.PATIENTS_WRITE + "') and hasAuthority('"
        + DentalPermissions.PREVISIT_READ + "')")
    public ResponseEntity<CommonResponse<PatientView>> apply(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ApplyRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(CommonResponse.success(previsits.apply(id, request.formVersion(), ifMatch)));
    }
}
