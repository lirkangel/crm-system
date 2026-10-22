package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void findById_returnsUserWhenRepositoryFindsOne() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        Optional<User> result = userService.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(user);
        verify(userRepository).findById(id);
    }

    @Test
    void findById_returnsEmptyWhenMissing() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(userService.findById(id)).isEmpty();
        verify(userRepository).findById(id);
    }

    @Test
    void findAll_delegatesToRepository() {
        User a = new User();
        a.setUsername("a");
        User b = new User();
        b.setUsername("b");
        when(userRepository.findAll()).thenReturn(List.of(a, b));

        List<User> result = userService.findAll();

        assertThat(result).containsExactly(a, b);
        verify(userRepository).findAll();
    }
}
