package com.huashuo.user.vo;

import java.time.LocalDateTime;

public record UserLoginResponse(
        Long userId,
        String username,
        String displayName,
        String token,
        LocalDateTime expiresAt
) {
}

