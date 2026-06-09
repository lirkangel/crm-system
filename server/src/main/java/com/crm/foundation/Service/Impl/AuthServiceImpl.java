package com.crm.foundation.Service.Impl;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.AuthResponse;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.FailureReason;
import com.crm.foundation.DTO.LoginResult;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.DTO.MeResponse;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.AuthService;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final TokenService tokenService;
    private final RoleService roleService;
    private final AuditService auditService;

    @Override
    public AuthResponse login(LoginRequest request, String sourceIp) {
        LoginResult result = userService.attemptLogin(request);

        if (result instanceof LoginResult.Failure(FailureReason reason)) {
            auditService.record(new AuditPayload(
                Instant.now(), null, sourceIp,
                null, null, null, "AUTH_LOGIN_FAILURE", null, null, "WARN"));
            String code = switch (reason) {
                case BAD_CREDENTIALS  -> "AUTH_LOGIN_INVALID_CREDENTIALS";
                case ACCOUNT_LOCKED   -> "AUTH_LOGIN_ACCOUNT_LOCKED";
                case ACCOUNT_DISABLED -> "AUTH_LOGIN_ACCOUNT_DISABLED";
            };
            String message = switch (reason) {
                case BAD_CREDENTIALS  -> "Invalid username or password";
                case ACCOUNT_LOCKED   -> "Account is temporarily locked";
                case ACCOUNT_DISABLED -> "Account is disabled";
            };
            throw new AuthException(code, message);
        }

        User u = ((LoginResult.Success) result).user();
        auditService.record(new AuditPayload(
            Instant.now(), u.getId(), sourceIp,
            null, "User", u.getId(), "AUTH_LOGIN_SUCCESS", null, null, "INFO"));
        Set<String> perms = roleService.permissionKeysForUser(u.getId());
        IssuedAccessToken access = tokenService.createAccessToken(u, perms);
        RefreshToken refresh = tokenService.createToken(u);
        return AuthResponse.from(access.token(), access.expiresAt(), refresh);
    }

    @Override
    public AuthResponse refresh(UUID refreshTokenJti) {
        RefreshToken refresh = tokenService.refreshToken(refreshTokenJti);
        if (refresh == null) {
            throw new AuthException("AUTH_REFRESH_INVALID", "Refresh token is invalid or expired");
        }
        User user = refresh.getUser();
        Set<String> perms = roleService.permissionKeysForUser(user.getId());
        IssuedAccessToken access = tokenService.createAccessToken(user, perms);
        return AuthResponse.from(access.token(), access.expiresAt(), refresh);
    }

    @Override
    public MeResponse me(UUID userId) {
        User user = userService.findById(userId)
            .orElseThrow(() -> new AuthException("AUTH_USER_NOT_FOUND", "User not found"));
        Set<String> permissions = roleService.permissionKeysForUser(userId);
        return MeResponse.from(user, permissions);
    }

    @Override
    public LogoutStatus revoke(UUID jti) {
        return tokenService.revokeToken(jti);
    }
}
