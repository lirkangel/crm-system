package com.crm.hotel.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Body of {@code POST /room-types} and {@code PUT /room-types/{id}}. */
public record RoomTypeRequest(
        @NotBlank @Size(max = 32) String code,
        @NotEmpty Map<String, String> nameI18n,
        @Min(1) int maxGuests,
        @PositiveOrZero long nightlyRateGrossVnd,
        boolean active) {
}
