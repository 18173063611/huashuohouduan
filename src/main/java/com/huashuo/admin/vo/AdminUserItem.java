package com.huashuo.admin.vo;

import java.time.LocalDateTime;

public record AdminUserItem(
        Long userId,
        String username,
        String displayName,
        String role,
        String status,
        String phone,
        String email,
        String remark,
        Long creditBalance,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
}
