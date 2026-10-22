package com.crm.foundation.Service;

import com.crm.foundation.DTO.AuthResponse;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.DTO.MeResponse;
import com.crm.foundation.Exception.AuthException;

import java.util.UUID;

/**
 * Application-level auth facade. Orchestrates UserService, TokenService,
 * RoleService, and AuditService so the controller only deals with HTTP concerns.
 *
 * <p>On failure, methods throw {@link AuthException} — the controller never
 * inspects failure details, that is handled globally by ErrorHandler.
 */
public interface AuthService {

    /**
     * Attempt login. Returns the token pair on success.
     * Throws {@link AuthException} on bad credentials, locked or disabled account.
     */
    AuthResponse login(LoginRequest request, String sourceIp);

    /**
     * Issue a new access token from a valid refresh JTI.
     * Throws {@link AuthException} if the token is invalid or expired.
     */
    AuthResponse refresh(UUID refreshTokenJti);

    /** Aggregate user profile + permissions for the given user ID. */
    MeResponse me(UUID userId);

    /** Revoke a refresh token by JTI (used by both /logout and /revoke). */
    LogoutStatus revoke(UUID jti);
}
