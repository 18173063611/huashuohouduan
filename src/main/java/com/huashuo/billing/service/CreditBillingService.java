package com.huashuo.billing.service;

import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.user.service.CreditChangeResult;

public interface CreditBillingService {

    CreditChangeResult precharge(Long userId, Long taskId, UsageEstimateResult estimate,
                                 String idempotencyKey, String remark);

    void settle(Long taskId, UsageActualResult actualUsage);
}
