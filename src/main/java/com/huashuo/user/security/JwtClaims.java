package com.huashuo.user.security;

import java.time.Instant;

public record JwtClaims(
        Long userId,
        String username,
        String role,
        String clientType,
        String sessionId,
        Long tokenVersion,
        String jti,
        Instant issuedAt,
        Instant expiresAt
) {
}
