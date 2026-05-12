package com.huashuo.user.service;

public record CreditChangeResult(
        Long creditLogId,
        Long userId,
        Long changeAmount,
        Long beforeBalance,
        Long afterBalance
) {
}
