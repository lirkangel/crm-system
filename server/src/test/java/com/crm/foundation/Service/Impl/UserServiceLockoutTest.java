package com.crm.foundation.Service.Impl;

import com.crm.foundation.DTO.LoginRequest;
import com.crm.foundation.DTO.LoginResult;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.ChangeEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceLockoutTest {

    static final String HASHED = BCrypt.hashpw("correct", BCrypt.gensalt());

    @Mock
    UserRepository userRepository;

    @Mock
    ChangeEventService changeEventService;

    @InjectMocks
    UserServiceImpl userService;

    // ── helpers ──────────────────────────────────────────────────────────────

    private User activeUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername("alice");
        u.setPassword(HASHED);
        u.setEnabled(true);
        u.setFailedLogin(0);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        return u;
    }

    private LoginRequest req(String username, String password) {
        return new LoginRequest(username, password);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void attemptLogin_unknownUsername_returnsBadCredentials() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        LoginResult result = userService.attemptLogin(req("nobody", "pw"));

        assertThat(result).isInstanceOf(LoginResult.Failure.class);
        assertThat(((LoginResult.Failure) result).reason())
                .isEqualTo(LoginResult.FailureReason.BAD_CREDENTIALS);
        verify(userRepository, never()).save(any());
    }

    @Test
    void attemptLogin_disabledAccount_returnsAccountDisabled() {
        User u = activeUser();
        u.setEnabled(false);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));

        LoginResult result = userService.attemptLogin(req("alice", "correct"));

        assertThat(result).isInstanceOf(LoginResult.Failure.class);
        assertThat(((LoginResult.Failure) result).reason())
                .isEqualTo(LoginResult.FailureReason.ACCOUNT_DISABLED);
        verify(userRepository, never()).save(any());
    }

    @Test
    void attemptLogin_lockedAccount_returnsAccountLocked() {
        User u = activeUser();
        u.setLockedUntil(Instant.now().plusSeconds(300));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));

        LoginResult result = userService.attemptLogin(req("alice", "correct"));

        assertThat(result).isInstanceOf(LoginResult.Failure.class);
        assertThat(((LoginResult.Failure) result).reason())
                .isEqualTo(LoginResult.FailureReason.ACCOUNT_LOCKED);
        verify(userRepository, never()).save(any());
    }

    @Test
    void attemptLogin_expiredLockout_proceedsToPasswordCheck() {
        User u = activeUser();
        u.setLockedUntil(Instant.now().minusSeconds(1)); // lock already expired
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginResult result = userService.attemptLogin(req("alice", "correct"));

        assertThat(result).isInstanceOf(LoginResult.Success.class);
    }

    @Test
    void attemptLogin_wrongPassword_returnsBadCredentials() {
        User u = activeUser();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginResult result = userService.attemptLogin(req("alice", "wrong"));

        assertThat(result).isInstanceOf(LoginResult.Failure.class);
        assertThat(((LoginResult.Failure) result).reason())
                .isEqualTo(LoginResult.FailureReason.BAD_CREDENTIALS);
    }

    @Test
    void attemptLogin_wrongPassword_incrementsFailedLogins() {
        User u = activeUser();
        u.setFailedLogin(2);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.attemptLogin(req("alice", "wrong"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getFailedLogin()).isEqualTo(3);
        assertThat(captor.getValue().getLockedUntil()).isNull(); // not yet at 5
    }

    @Test
    void attemptLogin_fifthBadPassword_setsLockedUntil() {
        User u = activeUser();
        u.setFailedLogin(4); // 4 previous failures; this is the 5th
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.attemptLogin(req("alice", "wrong"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getFailedLogin()).isEqualTo(5);
        assertThat(captor.getValue().getLockedUntil())
                .isAfter(Instant.now().plusSeconds(890)); // ~15 min from now
    }

    @Test
    void attemptLogin_correctPassword_returnsSuccessWithUser() {
        User u = activeUser();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginResult result = userService.attemptLogin(req("alice", "correct"));

        assertThat(result).isInstanceOf(LoginResult.Success.class);
        assertThat(((LoginResult.Success) result).user()).isSameAs(u);
    }

    @Test
    void attemptLogin_correctPassword_resetsFailedLoginsAndLock() {
        User u = activeUser();
        u.setFailedLogin(3);
        u.setLockedUntil(Instant.now().minusSeconds(1)); // expired lock
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.attemptLogin(req("alice", "correct"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getFailedLogin()).isZero();
        assertThat(captor.getValue().getLockedUntil()).isNull();
    }
}
