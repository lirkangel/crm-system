package com.crm.foundation.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
        @NotBlank String username,
        @Email @NotBlank String email,
        @NotBlank String password) {}
