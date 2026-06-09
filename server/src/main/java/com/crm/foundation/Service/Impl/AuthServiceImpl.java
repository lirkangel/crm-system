package com.crm.foundation.Service.Impl;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.AuthResponse;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.DTO.LoginOutcome;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LoginResult;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.DTO.MeResponse;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.AuthService;
import com.crm.foundation.Service.RoleService;
import com.crm.foundation.Service.TokenService;
import com.crm.foundation.Service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
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
    public LoginOutcome login(LoginRequest request, String sourceIp) {
        LoginResult result = userService.attemptLogin(request);

        if (result instanceof LoginResult.Failure failure) {
            auditService.record(new AuditPayload(
                Instant.now(), null, sourceIp,
                null, null, null, "AUTH_LOGIN_FAILURE", null, null, "WARN"));
            String code = switch (failure.reason()) {
                case BAD_CREDENTIALS -> "AUTH_LOGIN_INVALID_CREDENTIALS";
                case ACCOUNT_LOCKED  -> "AUTH_LOGIN_ACCOUNT_LOCKED";
                case ACCOUNT_DISABLED -> "AUTH_LOGIN_ACCOUNT_DISABLED";
            };
            String message = switch (failure.reason()) {
                case BAD_CREDENTIALS  -> "Invalid username or password";
                case ACCOUNT_LOCKED   -> "Account is temporarily locked";
                case ACCOUNT_DISABLED -> "Account is disabled";
            };
            return new LoginOutcome.Fail(code, message);
        }

        User u = ((LoginResult.Success) result).user();
        auditService.record(new AuditPayload(
            Instant.now(), u.getId(), sourceIp,
            null, "User", u.getId(), "AUTH_LOGIN_SUCCESS", null, null, "INFO"));
        Set<String> perms = roleService.permissionKeysForUser(u.getId());
        IssuedAccessToken access = tokenService.createAccessToken(u, perms);
        RefreshToken refresh = tokenService.createToken(u);
        return new LoginOutcome.Ok(AuthResponse.from(access.token(), access.expiresAt(), refresh));
    }

    @Override
    public Optional<AuthResponse> refresh(UUID refreshTokenJti) {
        RefreshToken refresh = tokenService.refreshToken(refreshTokenJti);
        if (refresh == null) return Optional.empty();
        User user = refresh.getUser();
        Set<String> perms = roleService.permissionKeysForUser(user.getId());
        IssuedAccessToken access = tokenService.createAccessToken(user, perms);
        return Optional.of(AuthResponse.from(access.token(), access.expiresAt(), refresh));
    }

    @Override
    public MeResponse me(UUID userId) {
        User user = userService.findById(userId)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
        Set<String> permissions = roleService.permissionKeysForUser(userId);
        return MeResponse.from(user, permissions);
    }

    @Override
    public LogoutStatus revoke(UUID jti) {
        return tokenService.revokeToken(jti);
    }
}
