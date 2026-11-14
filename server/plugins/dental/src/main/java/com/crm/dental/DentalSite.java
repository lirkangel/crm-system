package com.crm.dental;

/** Where patients open their personal link, the clinic name shown to them, and whether the public route is on. */
public record DentalSite(boolean publicEnabled, String guestBaseUrl, String displayName) {
}
