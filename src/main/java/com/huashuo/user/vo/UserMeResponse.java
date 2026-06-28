package com.huashuo.user.vo;

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
        /** 可用积分余额 */
        Long creditBalance,
        /** 冻结积分（预留字段，当前多为 0） */
        Long creditFrozenBalance,
        /** 累计消耗积分 */
        Long creditTotalConsumed
) {
}
