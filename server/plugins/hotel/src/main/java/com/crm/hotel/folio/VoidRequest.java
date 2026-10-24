package com.crm.hotel.folio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of the void endpoints: why the line was entered by mistake. */
public record VoidRequest(@NotBlank @Size(max = 500) String reason) {
}
