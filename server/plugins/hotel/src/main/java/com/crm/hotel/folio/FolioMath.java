package com.crm.hotel.folio;

/** The folio arithmetic, kept pure so it is testable without a database. */
public final class FolioMath {

    private FolioMath() {
    }

    /**
     * Balance due: the room charge plus the active extra charges minus the active payments (all gross VND).
     *
     * @throws ArithmeticException on {@code long} overflow
     */
    public static long balance(long roomChargeGrossVnd, long activeChargesGrossVnd, long activePaymentsGrossVnd) {
        return Math.subtractExact(Math.addExact(roomChargeGrossVnd, activeChargesGrossVnd), activePaymentsGrossVnd);
    }
}
