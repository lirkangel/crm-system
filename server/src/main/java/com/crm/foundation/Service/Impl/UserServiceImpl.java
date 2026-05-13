package com.crm.foundation.Service.Impl;

import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

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
    public User register(LoginRequest loginRequest) {
        if (checkUserByUsernamePassword(loginRequest)) {
            throw new BadRequestException("User already exists");
        }
        String username = Objects.requireNonNull(loginRequest.getUsername(), "username");
        String password = Objects.requireNonNull(loginRequest.getPassword(), "password");
        User newUser = new User();
        newUser.setUsername(username);
        newUser.setEmail(username + "@users.local");
        newUser.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        newUser.setEnabled(true);
        newUser.setCreatedAt(Instant.now());
        newUser.setUpdatedAt(Instant.now());
        return userRepository.save(newUser);
    }
}
