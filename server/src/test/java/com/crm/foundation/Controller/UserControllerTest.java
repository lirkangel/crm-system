package com.crm.foundation.Controller;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController();
        userController.userService = userService;
    }

    @Test
    void getUserById_returnsOptionalFromService() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");
        User user = new User();
        user.setId(id);
        user.setUsername("bob");
        when(userService.findById(id)).thenReturn(Optional.of(user));

        Optional<User> result = userController.GetUserById(id);

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(user);
        verify(userService).findById(id);
    }

    @Test
    void getUserById_returnsEmptyWhenServiceFindsNothing() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000404");
        when(userService.findById(id)).thenReturn(Optional.empty());

        assertThat(userController.GetUserById(id)).isEmpty();
        verify(userService).findById(id);
    }
}
