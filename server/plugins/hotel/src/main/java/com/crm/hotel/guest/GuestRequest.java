package com.crm.hotel.guest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /guests}; the email is optional. */
public record GuestRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Size(max = 32) String phone,
        @Email @Size(max = 254) String email) {
}
