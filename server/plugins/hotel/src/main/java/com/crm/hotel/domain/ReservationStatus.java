package com.crm.hotel.domain;

/** Lifecycle of a reservation; only {@code CONFIRMED -> CANCELLED} exists in this slice. */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED
}
