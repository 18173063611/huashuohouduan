package com.huashuo.user.vo;

import java.time.LocalDateTime;

public record UserLoginResponse(
        Long userId,
        String username,
        String displayName,
        String avatarUrl,
        String role,
        String status,
        Long creditBalance,
        String accessToken,
        String token,
        String clientType,
        String sessionId,
        LocalDateTime expiresAt
) {
}
