package com.crm.hotel.booking;

import java.util.UUID;

/** A free room of a room type for the searched stay. */
public record AvailableRoom(UUID id, String number, Integer floor) {
}
