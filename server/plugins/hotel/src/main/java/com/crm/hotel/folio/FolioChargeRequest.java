package com.crm.hotel.folio;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /reservations/{id}/folio/charges}. */
public record FolioChargeRequest(
        @NotBlank @Size(max = 200) String description,
        @NotNull @Positive @Max(1_000_000_000_000L) Long amountGrossVnd) {
}
