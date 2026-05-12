package com.huashuo.user.service;

import com.huashuo.user.entity.UserCreditAccountEntity;

public interface CreditService {

    UserCreditAccountEntity ensureAccount(Long userId);

    CreditChangeResult consumeForTask(Long userId, Long taskId, String modelCode, long amount,
                                      String idempotencyKey, String remark);

    CreditChangeResult refundForTask(Long userId, Long taskId, String modelCode, long amount,
                                     String idempotencyKey, String remark);
}
