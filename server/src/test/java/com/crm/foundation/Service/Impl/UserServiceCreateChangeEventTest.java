package com.crm.foundation.Service.Impl;

import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.ChangeEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceCreateChangeEventTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChangeEventService changeEventService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void createUser_emitsChangeEvent() {
        UUID newId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        User saved = new User();
        saved.setId(newId);
        saved.setUsername("dave");
        saved.setEmail("dave@hotel.local");
        saved.setEnabled(true);
        saved.setFailedLogin(0);
        when(userRepository.save(any(User.class))).thenReturn(saved);

        userService.createUser(new CreateUserRequest("dave", "dave@hotel.local", "secret"));

        ArgumentCaptor<String> opCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(changeEventService).record(
            org.mockito.ArgumentMatchers.eq("core"),
            org.mockito.ArgumentMatchers.eq("User"),
            idCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(1L),
            opCaptor.capture(),
            any()
        );
        assertThat(idCaptor.getValue()).isEqualTo(newId);
        assertThat(opCaptor.getValue()).isEqualTo("CREATE");
    }
}
