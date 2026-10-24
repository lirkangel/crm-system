package com.crm.hotel.folio;

import java.time.Instant;
import java.util.UUID;

/** A payment as served by the API (whole VND, gross); voided lines stay listed. */
public record FolioPaymentView(
        UUID id,
        UUID reservationId,
        String method,
        String otaChannel,
        String reference,
        long amountGrossVnd,
        Instant createdAt,
        Instant voidedAt,
        String voidReason,
        long version) {

    static FolioPaymentView of(FolioPayment payment) {
        return new FolioPaymentView(payment.getId(), payment.getReservationId(), payment.getMethod().name(),
            payment.getOtaChannel() == null ? null : payment.getOtaChannel().name(), payment.getReference(),
            payment.getAmountGrossVnd(), payment.getCreatedAt(), payment.getVoidedAt(), payment.getVoidReason(),
            payment.getVersion());
    }
}
