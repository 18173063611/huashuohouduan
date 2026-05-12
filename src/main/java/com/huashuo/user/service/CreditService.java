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
}
