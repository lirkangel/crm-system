package com.crm.hotel.frontdesk;

import com.crm.hotel.booking.ReservationView;

/**
 * One line of a front-desk list. {@code late}: an arrival whose check-in day has passed. {@code overdue}: a stay
 * still in the house past its check-out day. Each list sets only its own flag.
 */
public record FrontDeskEntry(ReservationView reservation, boolean late, boolean overdue) {
}
