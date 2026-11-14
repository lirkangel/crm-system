package com.crm.dental.patient;

import com.crm.dental.DentalPermissions;
import com.crm.foundation.DTO.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/dental}. */
@RestController
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patients;

    /** Both parameters are optional at the binding level so a missing pair is the clinic's 400, not a generic one. */
    @GetMapping("/patients")
    @PreAuthorize("hasAuthority('" + DentalPermissions.PATIENTS_READ + "')")
    public CommonResponse<List<PatientView>> search(
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "q", required = false) String q) {
        return CommonResponse.success(patients.search(phone, q));
    }

    @PostMapping("/patients")
    @PreAuthorize("hasAuthority('" + DentalPermissions.PATIENTS_WRITE + "')")
    public ResponseEntity<CommonResponse<PatientView>> create(@Valid @RequestBody PatientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(patients.create(request)));
    }

    /** The {@code If-Match} header is optional here so a missing one is the clinic's 428, not a generic 400. */
    @PutMapping("/patients/{id}")
    @PreAuthorize("hasAuthority('" + DentalPermissions.PATIENTS_WRITE + "')")
    public CommonResponse<PatientView> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody PatientRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return CommonResponse.success(patients.update(id, request, ifMatch));
    }
}
