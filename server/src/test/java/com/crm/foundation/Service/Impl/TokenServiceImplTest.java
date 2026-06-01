package com.crm.foundation.Service.Impl;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.DTO.LogoutStatus;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
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

    private JwtTokenProvider jwtTokenProvider;

    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider("abcdefghijklmnopqrstuvwxyz123456", 900L);
        tokenService = new TokenServiceImpl(refreshTokenRepository, 900L, jwtTokenProvider);
    }

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
    void revokeToken_returnsFalseWhenJtiNotFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000077"));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(null);

        assertThat(tokenService.revokeToken(jti)).isEqualTo(LogoutStatus.NOT_FOUND);
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void revokeToken_returnsRevokedAndSetsRevokedAtWhenJtiFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000066"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("v");
        user.setEmail("v@e.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.parse("2030-01-01T00:00:00Z"));
        token.setJti(jti);
        Instant before = Instant.now();
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.revokeToken(jti)).isEqualTo(LogoutStatus.REVOKED);
        assertThat(token.getRevokedAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
        assertThat(token.getRevokedAt()).isAfterOrEqualTo(before);
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void revokeToken_returnsAlreadyRevokedWhenRevokedAtAlreadySet() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000067"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("revoked");
        user.setEmail("revoked@example.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.now().plusSeconds(900));
        token.setJti(jti);
        token.setRevokedAt(Instant.now().minusSeconds(5));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.revokeToken(jti)).isEqualTo(LogoutStatus.ALREADY_REVOKED);
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void revokeToken_returnsAlreadyUsedWhenUsedAtAlreadySet() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000068"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("used");
        user.setEmail("used@example.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.now().plusSeconds(900));
        token.setJti(jti);
        token.setUsedAt(Instant.now().minusSeconds(5));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.revokeToken(jti)).isEqualTo(LogoutStatus.ALREADY_USED);
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void revokeToken_returnsExpiredWhenTokenAlreadyExpired() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000069"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("expired");
        user.setEmail("expired@example.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.now().minusSeconds(60));
        token.setJti(jti);
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.revokeToken(jti)).isEqualTo(LogoutStatus.EXPIRED);
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void refreshToken_returnsNullWhenJtiNotFound() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000055"));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(null);

        assertThat(tokenService.refreshToken(jti)).isNull();
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void refreshToken_returnsNullWhenTokenIsExpired() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000056"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("expired");
        user.setEmail("expired@example.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.now().minusSeconds(60));
        token.setJti(jti);
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.refreshToken(jti)).isNull();
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getReplacedBy()).isNull();
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void refreshToken_returnsNullWhenTokenWasAlreadyUsed() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000057"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("used");
        user.setEmail("used@example.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.now().plusSeconds(900));
        token.setJti(jti);
        token.setUsedAt(Instant.now().minusSeconds(10));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.refreshToken(jti)).isNull();
        assertThat(token.getReplacedBy()).isNull();
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    void refreshToken_returnsNullWhenTokenWasRevoked() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000059"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("revoked");
        user.setEmail("revoked@example.com");
        RefreshToken token = RefreshToken.issueFor(user, Instant.now().plusSeconds(900));
        token.setJti(jti);
        token.setRevokedAt(Instant.now().minusSeconds(10));
        when(refreshTokenRepository.findByJti(jti)).thenReturn(token);

        assertThat(tokenService.refreshToken(jti)).isNull();
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getReplacedBy()).isNull();
        verify(refreshTokenRepository).findByJti(jti);
    }

    @Test
    @SuppressWarnings("null") // Mockito captor/saveAndFlush nullness annotations
    void refreshToken_rotatesTokenAndMarksOriginalAsUsed() {
        UUID jti = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000058"));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("rotate");
        user.setEmail("rotate@example.com");
        RefreshToken existing = RefreshToken.issueFor(user, Instant.now().plusSeconds(900));
        existing.setJti(jti);

        when(refreshTokenRepository.findByJti(jti)).thenReturn(existing);
        when(refreshTokenRepository.saveAndFlush(any()))
            .thenAnswer(invocation -> Objects.requireNonNull(invocation.getArgument(0, RefreshToken.class)));

        RefreshToken result = tokenService.refreshToken(jti);

        assertThat(result).isNotNull();
        assertThat(result.getUser()).isSameAs(user);
        assertThat(result.getJti()).isNotEqualTo(jti);
        assertThat(result.getExpiresAt()).isAfter(Instant.now());
        assertThat(existing.getUsedAt()).isNotNull();
        assertThat(existing.getReplacedBy()).isEqualTo(result.getJti());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).getJti()).isEqualTo(result.getJti());
        assertThat(captor.getAllValues().get(1).getJti()).isEqualTo(existing.getJti());
    }
}
