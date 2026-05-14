package com.huashuo.user.service;

import com.huashuo.user.entity.UserCreditAccountEntity;

public interface CreditService {

    UserCreditAccountEntity ensureAccount(Long userId);

    /**
     * 提交消耗积分的任务前只读校验：余额不足则抛出业务异常，不写任务、不预扣。
     */
    void assertBalanceAtLeast(Long userId, long minimumAmount);

    CreditChangeResult consumeForTask(Long userId, Long taskId, String modelCode, long amount,
                                      String idempotencyKey, String remark);

    CreditChangeResult refundForTask(Long userId, Long taskId, String modelCode, long amount,
                                     String idempotencyKey, String remark);

    /**
     * 只读获取当前余额，不创建账户、不抛异常；账户不存在返回 0。
     * 用于 settle 阶段判断是否可以完成补扣，避免直接调 {@link #consumeForTask} 触发"余额不足"异常。
     */
    long getBalance(Long userId);

    /**
     * 限额扣减：最多扣 {@code maxAmount}，实际只扣当前余额内允许的部分，写一条 {@code AI_CONSUME} 流水。
     *
     * @return 实际扣减的金额（{@code [0, maxAmount]}）；若幂等键已存在则返回历史流水的扣减金额。
     */
    long consumeUpTo(Long userId, Long taskId, String modelCode, long maxAmount,
                     String idempotencyKey, String remark);
}
