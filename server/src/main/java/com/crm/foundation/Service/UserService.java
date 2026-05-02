package com.crm.foundation.Service;

import com.crm.foundation.Domain.User;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserService {
    Optional<User> findById(@NonNull UUID id);

    List<User> findAll();
}
