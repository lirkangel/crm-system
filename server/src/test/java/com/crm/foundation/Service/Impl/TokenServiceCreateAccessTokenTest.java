package com.crm.foundation.Service.Impl;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.DTO.IssuedAccessToken;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TokenServiceCreateAccessTokenTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        JwtTokenProvider jwtTokenProvider =
                new JwtTokenProvider("abcdefghijklmnopqrstuvwxyz123456", 900L);
        tokenService =
                new TokenServiceImpl(
                        refreshTokenRepository,
                        900L,
                        jwtTokenProvider);
    }

    @Test
    void createAccessToken_issuesTokenDirectlyFromGivenUser() {
        User user = new User();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000055"));
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        IssuedAccessToken issued = tokenService.createAccessToken(user);

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isNotNull();
    }
}
