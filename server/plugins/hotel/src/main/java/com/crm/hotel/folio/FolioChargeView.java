package com.crm.hotel.folio;

import java.time.Instant;
import java.util.UUID;

/** An extra charge as served by the API (whole VND, gross); voided lines stay listed. */
public record FolioChargeView(
        UUID id,
        UUID reservationId,
        String description,
        long amountGrossVnd,
        Instant createdAt,
        Instant voidedAt,
        String voidReason,
        long version) {

    static FolioChargeView of(FolioCharge charge) {
        return new FolioChargeView(charge.getId(), charge.getReservationId(), charge.getDescription(),
            charge.getAmountGrossVnd(), charge.getCreatedAt(), charge.getVoidedAt(), charge.getVoidReason(),
            charge.getVersion());
    }
}
