package com.crm.hotel.folio;

import java.util.List;
import java.util.UUID;

/**
 * A stay's folio: the room charge (the booking total), every charge and payment line (voided ones marked, excluded
 * from the totals) and the balance due.
 */
public record FolioView(
        UUID reservationId,
        String status,
        long roomChargeGrossVnd,
        List<FolioChargeView> charges,
        List<FolioPaymentView> payments,
        long chargesTotalGrossVnd,
        long paymentsTotalGrossVnd,
        long balanceGrossVnd) {
}
