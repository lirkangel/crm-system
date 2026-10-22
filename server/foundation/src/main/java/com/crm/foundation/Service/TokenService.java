package com.crm.foundation.Service;

import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;

import java.util.Set;
import java.util.UUID;

public interface TokenService {

    RefreshToken createToken(User user);

    RefreshToken refreshToken(UUID jti);

    LogoutStatus revokeToken(UUID jti);

    IssuedAccessToken createAccessToken(User user, Set<String> permissions);
}
