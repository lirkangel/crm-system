package com.crm.foundation.Controller;

import com.crm.foundation.DTO.UserResponse;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    @Test
    void getUserById_returnsResponseBodyFromService() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");
        User user = new User();
        user.setId(id);
        user.setUsername("bob");
        user.setEmail("bob@example.com");
        user.setEnabled(true);
        when(userService.findById(id)).thenReturn(Optional.of(user));

        ResponseEntity<UserResponse> result = userController.getUserById(id);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(
                new UserResponse(id, "bob", "bob@example.com", true));
        verify(userService).findById(id);
    }

    @Test
    void getUserById_returnsNotFoundWhenServiceFindsNothing() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000404");
        when(userService.findById(id)).thenReturn(Optional.empty());

        ResponseEntity<UserResponse> result = userController.getUserById(id);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNull();
        verify(userService).findById(id);
    }
}
