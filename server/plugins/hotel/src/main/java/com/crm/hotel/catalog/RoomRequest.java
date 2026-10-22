package com.crm.hotel.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /rooms} and {@code PUT /rooms/{id}}. {@code inService} is required:
 * an omitted flag must not silently take a room out of availability.
 */
public record RoomRequest(
        @NotBlank @Size(max = 16) String number,
        @NotNull UUID roomTypeId,
        Integer floor,
        @NotNull Boolean inService) {
}
