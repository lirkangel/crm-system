package com.crm.hotel.folio;

/** How a folio payment was made; {@code OTA} means the travel agency collected it (prepaid, or paid to the hotel later). */
public enum PaymentMethod {
    CASH,
    CARD,
    BANK_TRANSFER,
    OTA
}
