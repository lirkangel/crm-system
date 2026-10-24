package com.crm.hotel.folio;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Served under {@code /api/v1/plugins/hotel}; every response is the whole folio after the change. */
@RestController
@RequiredArgsConstructor
public class FolioController {

    private final FolioService folios;

    @GetMapping("/reservations/{id}/folio")
    @PreAuthorize("hasAuthority('" + HotelPermissions.FOLIO_READ + "')")
    public CommonResponse<FolioView> folio(@PathVariable("id") UUID id) {
        return CommonResponse.success(folios.folio(id));
    }

    @PostMapping("/reservations/{id}/folio/charges")
    @PreAuthorize("hasAuthority('" + HotelPermissions.FOLIO_CHARGE + "')")
    public ResponseEntity<CommonResponse<FolioView>> addCharge(
            @PathVariable("id") UUID id, @Valid @RequestBody FolioChargeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(folios.addCharge(id, request)));
    }

    @PostMapping("/reservations/{id}/folio/charges/{chargeId}/void")
    @PreAuthorize("hasAuthority('" + HotelPermissions.FOLIO_CHARGE + "')")
    public CommonResponse<FolioView> voidCharge(
            @PathVariable("id") UUID id, @PathVariable("chargeId") UUID chargeId,
            @Valid @RequestBody VoidRequest request) {
        return CommonResponse.success(folios.voidCharge(id, chargeId, request));
    }

    @PostMapping("/reservations/{id}/folio/payments")
    @PreAuthorize("hasAuthority('" + HotelPermissions.FOLIO_PAY + "')")
    public ResponseEntity<CommonResponse<FolioView>> addPayment(
            @PathVariable("id") UUID id, @Valid @RequestBody FolioPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(folios.addPayment(id, request)));
    }

    @PostMapping("/reservations/{id}/folio/payments/{paymentId}/void")
    @PreAuthorize("hasAuthority('" + HotelPermissions.FOLIO_PAY + "')")
    public CommonResponse<FolioView> voidPayment(
            @PathVariable("id") UUID id, @PathVariable("paymentId") UUID paymentId,
            @Valid @RequestBody VoidRequest request) {
        return CommonResponse.success(folios.voidPayment(id, paymentId, request));
    }
}
