package com.huashuo.user.vo;

/**
 * 当前登录用户积分账户快照（与 /auth/me 中积分字段同源）。
 */
public record UserCreditMeResponse(
        Long userId,
        long balance,
        long frozenBalance,
        long totalRecharged,
        long totalConsumed
) {
}
