package com.crm.foundation.Service;

import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.Domain.User;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserService {
    Optional<User> findById(@NonNull UUID id);

    List<User> findAll();

    Optional<User> findByUsername(@NonNull String username);

    Boolean checkUserByUsernamePassword(@NotNull LoginRequest loginRequest);

    User register(@NotNull LoginRequest loginRequest);
}
