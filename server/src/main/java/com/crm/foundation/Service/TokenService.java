package com.crm.foundation.Service;

import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;

import java.util.UUID;

public interface TokenService {

    RefreshToken createToken(User user);

    RefreshToken updateToken(UUID jti);

    Boolean revokeToken(UUID jti);
}
