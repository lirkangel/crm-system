package com.crm.foundation.Service.Impl;

import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LoginResult;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.ChangeEventService;
import com.crm.foundation.Service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final ChangeEventService changeEventService;

    @Override
    public Optional<User> findById(@NonNull UUID id) {
        return userRepository.findById(id);
    }

    @Override
    public List<User> findAll() {
        return userRepository.findAll();
    }

    @Override
    public Optional<User> findByUsername(@NonNull String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public Boolean checkUserByUsernamePassword(LoginRequest loginRequest) {
        String username = Objects.requireNonNull(loginRequest.getUsername(), "username");
        String password = Objects.requireNonNull(loginRequest.getPassword(), "password");
        Optional<User> existingUser = findByUsername(username);
        return existingUser.filter(user -> BCrypt.checkpw(password, user.getPassword())).isPresent();
    }

    @Override
    @Transactional
    public LoginResult attemptLogin(LoginRequest loginRequest) {
        String username = Objects.requireNonNull(loginRequest.getUsername(), "username");
        String password = Objects.requireNonNull(loginRequest.getPassword(), "password");

        Optional<User> maybeUser = userRepository.findByUsername(username);
        if (maybeUser.isEmpty()) {
            return new LoginResult.Failure(LoginResult.FailureReason.BAD_CREDENTIALS);
        }

        User user = maybeUser.get();

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            return new LoginResult.Failure(LoginResult.FailureReason.ACCOUNT_DISABLED);
        }

        Instant lockedUntil = user.getLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            return new LoginResult.Failure(LoginResult.FailureReason.ACCOUNT_LOCKED);
        }

        if (!BCrypt.checkpw(password, user.getPassword())) {
            int newCount = user.getFailedLogin() + 1;
            user.setFailedLogin(newCount);
            if (newCount >= 5) {
                user.setLockedUntil(Instant.now().plusSeconds(15 * 60));
            }
            userRepository.save(user);
            return new LoginResult.Failure(LoginResult.FailureReason.BAD_CREDENTIALS);
        }

        user.setFailedLogin(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        return new LoginResult.Success(user);
    }

    @Override
    @Transactional
    public User createUser(CreateUserRequest request) {
        User newUser = new User();
        newUser.setUsername(Objects.requireNonNull(request.username(), "username"));
        newUser.setEmail(Objects.requireNonNull(request.email(), "email"));
        newUser.setPassword(BCrypt.hashpw(
            Objects.requireNonNull(request.password(), "password"), BCrypt.gensalt()));
        newUser.setEnabled(true);
        newUser.setFailedLogin(0);
        newUser.setCreatedAt(Instant.now());
        newUser.setUpdatedAt(Instant.now());
        User created = userRepository.save(newUser);
        changeEventService.record("core", "User", created.getId(), 1L, "CREATE", Instant.now());
        return created;
    }
}
