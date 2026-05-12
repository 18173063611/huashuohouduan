package com.huashuo.admin.vo;

public record AdminCreditAccountResponse(
        Long userId,
        long balance,
        long frozenBalance,
        long totalRecharged,
        long totalConsumed
) {
}
