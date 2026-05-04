package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceImplTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private TokenServiceImpl tokenService;

    @Test
    @SuppressWarnings("null") // Mockito any()/ArgumentCaptor.capture() are not @NonNull for JDT null analysis
    void createToken_savesNewTokenWhenUserHasNone() {
        User user = new User();
        user.setId(Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        when(refreshTokenRepository.findByUser(user)).thenReturn(Optional.empty());
        when(refreshTokenRepository.saveAndFlush(any()))
                .thenAnswer(
                        invocation ->
                                Objects.requireNonNull(
                                        invocation.getArgument(0, RefreshToken.class)));

        RefreshToken result = tokenService.createToken(user);

        assertThat(result.getUser()).isSameAs(user);
        assertThat(result.getJti()).isNotNull();
        assertThat(result.getExpiresAt()).isAfter(Instant.now());
        assertThat(result.getCreatedAt()).isNotNull();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
    }

    @Test
    @SuppressWarnings("null") // Mockito when/verify with entity reference vs @NonNull repository API
    void createToken_updatesExpiryAndSavesWhenUserAlreadyHasToken() {
        User user = new User();
        user.setId(Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000002")));
        user.setUsername("bob");
        user.setEmail("bob@example.com");
        Instant oldExpiry = Instant.parse("2020-01-01T00:00:00Z");
        RefreshToken existing =
                Objects.requireNonNull(RefreshToken.issueFor(user, oldExpiry));
        when(refreshTokenRepository.findByUser(user)).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.saveAndFlush(existing)).thenReturn(existing);

        RefreshToken result = tokenService.createToken(user);

        assertThat(result).isSameAs(existing);
        assertThat(result.getExpiresAt()).isAfter(oldExpiry);
        verify(refreshTokenRepository).saveAndFlush(existing);
    }

    @Test
    void updateToken_returnsNullWhenJtiNotFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(null);

        assertThat(tokenService.updateToken(jti)).isNull();
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void updateToken_refreshesExpiryWhenJtiFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000088"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("u");
        user.setEmail("u@e.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.EPOCH);
        token.setJti(jti);
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        RefreshToken result = tokenService.updateToken(jti);

        assertThat(result).isSameAs(token);
        assertThat(token.getExpiresAt()).isAfter(Instant.EPOCH);
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void issueToken_returnsFalseWhenJtiNotFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000077"));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(null);

        assertThat(tokenService.issueToken(jti)).isFalse();
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void issueToken_returnsTrueAndSetsExpiresAtWhenJtiFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000066"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("v");
        user.setEmail("v@e.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.parse("2030-01-01T00:00:00Z"));
        token.setJti(jti);
        Instant before = Instant.now();
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.issueToken(jti)).isTrue();
        assertThat(token.getExpiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
        assertThat(token.getExpiresAt()).isAfterOrEqualTo(before);
        verify(refreshTokenRepository).findByJti(jti);
    }
}
