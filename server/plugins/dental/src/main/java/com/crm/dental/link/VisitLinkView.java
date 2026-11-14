package com.crm.dental.link;

import java.time.Instant;

/** Whether the appointment has a personal pre-visit link and when it was made and expires (never the token). */
public record VisitLinkView(boolean exists, Instant createdAt, Instant expiresAt) {

    static VisitLinkView none() {
        return new VisitLinkView(false, null, null);
    }
}
