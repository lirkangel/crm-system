package com.crm.foundation.Service.Impl;

import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceCreateTest {

    @Mock
    UserRepository userRepository;

    @InjectMocks
    UserServiceImpl userService;

    @Test
    void createUser_storesPasswordAsBcryptHash() {
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.createUser(new CreateUserRequest("alice", "alice@example.com", "plaintext"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        String stored = captor.getValue().getPassword();
        assertThat(stored).startsWith("$2");     // BCrypt prefix ($2a$, $2b$)
        assertThat(stored).isNotEqualTo("plaintext");
    }

    @Test
    void createUser_persistsAllSuppliedFields() {
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.createUser(new CreateUserRequest("bob", "bob@hotel.local", "pw"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("bob");
        assertThat(saved.getEmail()).isEqualTo("bob@hotel.local");
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getFailedLogin()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}
