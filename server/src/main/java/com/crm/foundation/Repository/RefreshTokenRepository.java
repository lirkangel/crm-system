package com.crm.foundation.Repository;

import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    RefreshToken findByJti(UUID jti);

    Optional<RefreshToken> findByUser(User user);
}
