package com.crm.hotel.folio;

import com.crm.hotel.domain.OtaChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /reservations/{id}/folio/payments}. {@code otaChannel} is required exactly when the method is
 * {@code OTA} (else 400 {@code HOTEL_INVALID_PAYMENT}); {@code reference} is e.g. the OTA booking number, card slip
 * or transfer reference.
 */
public record FolioPaymentRequest(
        @NotNull PaymentMethod method,
        OtaChannel otaChannel,
        @Size(max = 64) String reference,
        @NotNull @Positive Long amountGrossVnd) {
}
