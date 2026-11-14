package com.crm.dental.appointment;

import com.crm.dental.DentalErrors;
import com.crm.dental.DentalPermissions;
import com.crm.foundation.DTO.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/dental}. */
@RestController
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointments;

    /** Booking with an inline new patient needs only this permission, not {@code dental.patients.write}. */
    @PostMapping("/appointments")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public ResponseEntity<CommonResponse<AppointmentView>> book(@Valid @RequestBody BookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(appointments.book(request)));
    }

    /** The clinic-local day {@code date=YYYY-MM-DD}, required (400 {@code DENTAL_INVALID_QUERY}). */
    @GetMapping("/appointments")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_READ + "')")
    public CommonResponse<List<AppointmentView>> listDay(@RequestParam(value = "date", required = false) String date) {
        if (date == null || date.isBlank()) {
            throw DentalErrors.invalidQuery("date (YYYY-MM-DD) is required");
        }
        try {
            return CommonResponse.success(appointments.listDay(LocalDate.parse(date.trim())));
        } catch (DateTimeParseException e) {
            throw DentalErrors.invalidQuery("date must be YYYY-MM-DD");
        }
    }

    @GetMapping("/appointments/{id}")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_READ + "')")
    public CommonResponse<AppointmentView> get(@PathVariable("id") UUID id) {
        return CommonResponse.success(appointments.get(id));
    }

    /** The {@code If-Match} header is optional here so a missing one is the clinic's 428, not a generic 400. */
    @PatchMapping("/appointments/{id}")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public CommonResponse<AppointmentView> reschedule(
            @PathVariable("id") UUID id,
            @Valid @RequestBody RescheduleRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return CommonResponse.success(appointments.reschedule(id, request, ifMatch));
    }

    @PostMapping("/appointments/{id}/cancel")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public CommonResponse<AppointmentView> cancel(@PathVariable("id") UUID id) {
        return CommonResponse.success(appointments.cancel(id));
    }

    @PostMapping("/appointments/{id}/arrive")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public CommonResponse<AppointmentView> arrive(@PathVariable("id") UUID id) {
        return CommonResponse.success(appointments.arrive(id));
    }

    @PostMapping("/appointments/{id}/no-show")
    @PreAuthorize("hasAuthority('" + DentalPermissions.APPOINTMENTS_WRITE + "')")
    public CommonResponse<AppointmentView> noShow(@PathVariable("id") UUID id) {
        return CommonResponse.success(appointments.noShow(id));
    }
}
