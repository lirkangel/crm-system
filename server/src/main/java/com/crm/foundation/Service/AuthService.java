package com.crm.foundation.Service;

import com.crm.foundation.DTO.AuthResponse;
import com.crm.foundation.DTO.LoginOutcome;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.DTO.MeResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * Application-level auth facade. Orchestrates UserService, TokenService,
 * RoleService, and AuditService so the controller only deals with HTTP concerns.
 */
public interface AuthService {

    /** Attempt login; returns Ok(AuthResponse) or Fail(code, message). */
    LoginOutcome login(LoginRequest request, String sourceIp);

    /** Issue a new access token from a valid refresh JTI; empty if invalid/expired. */
    Optional<AuthResponse> refresh(UUID refreshTokenJti);

    /** Aggregate user profile + permissions for the given user ID. */
    MeResponse me(UUID userId);

    /** Revoke a refresh token by JTI (used by both /logout and /revoke). */
    LogoutStatus revoke(UUID jti);
}
