package com.huashuo.billing.service;

import com.huashuo.billing.model.UsageEstimateResult;

public interface UsageEstimateService {

    UsageEstimateResult estimate(String taskType, String modelCode, String inputJson, Long fixedCreditFallback);
}
