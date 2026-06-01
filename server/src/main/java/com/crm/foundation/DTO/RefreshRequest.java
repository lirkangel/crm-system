package com.crm.foundation.DTO;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RefreshRequest(@NotNull UUID refreshToken) {
}
