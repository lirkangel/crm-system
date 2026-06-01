package com.crm.foundation.DTO;

public record LogoutResponse(
    LogoutStatus status,
    String message
) {
}
