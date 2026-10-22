package com.crm.hotel.guest;

import com.crm.hotel.domain.Guest;

import java.util.UUID;

/** A guest as served by the API. */
public record GuestView(UUID id, String fullName, String phone, String email, long version) {

    static GuestView of(Guest guest) {
        return new GuestView(guest.getId(), guest.getFullName(), guest.getPhone(), guest.getEmail(), guest.getVersion());
    }
}
