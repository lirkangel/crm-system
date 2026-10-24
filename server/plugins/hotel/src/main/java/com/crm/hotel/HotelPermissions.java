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
    public static final String STAYS_CHECKIN = "hotel.stays.checkin";
    public static final String STAYS_CHECKOUT = "hotel.stays.checkout";
    /** Identity records are personal data, hence their own permission. */
    public static final String STAYS_READ = "hotel.stays.read";
    public static final String FOLIO_READ = "hotel.folio.read";
    /** Add and void extra charges. */
    public static final String FOLIO_CHARGE = "hotel.folio.charge";
    /** Add and void payments. */
    public static final String FOLIO_PAY = "hotel.folio.pay";

    private HotelPermissions() {
    }
}
