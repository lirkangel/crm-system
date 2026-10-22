package com.crm.foundation.DTO;

import java.time.Instant;

/** Access JWT string and the expiry instant used when the token was minted (matches JWT {@code exp}). */
public record IssuedAccessToken(String token, Instant expiresAt) {}
