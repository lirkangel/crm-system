package com.crm.hotel.domain;

/**
 * Lifecycle of a reservation: {@code CONFIRMED -> CHECKED_IN -> CHECKED_OUT}, {@code CONFIRMED -> CANCELLED},
 * and undoing a check-in ({@code CHECKED_IN -> CONFIRMED}). Only {@link #CONFIRMED} and {@link #CHECKED_IN}
 * stays block their room.
 */
public enum ReservationStatus {
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED
}
