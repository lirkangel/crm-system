package com.crm.hotel;

/** Permission keys; they must match {@code permissions-declared} in {@code plugin.yaml}. */
public final class HotelPermissions {

    public static final String ROOMS_READ = "hotel.rooms.read";
    public static final String ROOMS_MANAGE = "hotel.rooms.manage";
    public static final String GUESTS_READ = "hotel.guests.read";
    public static final String GUESTS_MANAGE = "hotel.guests.manage";
    public static final String RESERVATIONS_READ = "hotel.reservations.read";
    public static final String RESERVATIONS_CREATE = "hotel.reservations.create";
    public static final String RESERVATIONS_CANCEL = "hotel.reservations.cancel";

    private HotelPermissions() {
    }
}
