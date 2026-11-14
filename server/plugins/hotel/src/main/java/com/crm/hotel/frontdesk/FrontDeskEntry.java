package com.crm.hotel.frontdesk;

import com.crm.hotel.booking.ReservationView;

/**
 * One line of a front-desk list. {@code late}: an arrival whose check-in day has passed. {@code overdue}: a stay
 * still in the house past its check-out day. {@code preArrivalAdults}: how many adults have sent their details
 * through the online check-in link (arrivals only, 0 elsewhere). Each list sets only its own fields.
 */
public record FrontDeskEntry(ReservationView reservation, boolean late, boolean overdue, int preArrivalAdults) {
}
