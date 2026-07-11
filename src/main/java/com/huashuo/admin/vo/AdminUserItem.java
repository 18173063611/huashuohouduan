package com.huashuo.admin.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AdminUserItem(
        Long userId,
        String username,
        String displayName,
        String role,
        String status,
        String phone,
        String email,
        String remark,
        List<String> permissions,
        Long creditBalance,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
}
