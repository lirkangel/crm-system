package com.crm.hotel;

/**
 * The guest-facing site: {@code baseUrl} (no trailing slash) is where personal check-in links point, and
 * {@code displayName} the hotel's name shown to guests.
 */
public record GuestSite(String baseUrl, String displayName) {
}
