package com.huashuo.user.vo;

import java.time.LocalDateTime;
import java.util.List;

public record UserLoginResponse(
        Long userId,
        String username,
        String displayName,
        String avatarUrl,
        String role,
        String status,
        List<String> permissions,
        List<String> features,
        Long creditBalance,
        String accessToken,
        String token,
        String clientType,
        String sessionId,
        LocalDateTime expiresAt
) {
}
