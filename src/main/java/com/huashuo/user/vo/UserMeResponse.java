package com.huashuo.user.vo;

public record UserMeResponse(
        Long userId,
        String username,
        String displayName
) {
}

