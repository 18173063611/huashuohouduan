package com.huashuo.user.vo;

import java.util.List;

public record UserMeResponse(
        Long userId,
        String username,
        String displayName,
        String avatarUrl,
        String phone,
        String email,
        String remark,
        String role,
        String status,
        List<String> permissions,
        List<String> features,
        Long creditBalance,
        Long creditFrozenBalance,
        Long creditTotalConsumed
) {
}
