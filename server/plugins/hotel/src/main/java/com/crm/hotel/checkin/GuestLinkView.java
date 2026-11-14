package com.crm.hotel.checkin;

import java.time.Instant;

/** Whether the reservation has a personal check-in link and when it was made and expires (never the token). */
public record GuestLinkView(boolean exists, Instant createdAt, Instant expiresAt) {

    static GuestLinkView none() {
        return new GuestLinkView(false, null, null);
    }
}
