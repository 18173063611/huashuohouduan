package com.huashuo.billing.service.impl;

import com.huashuo.billing.entity.AiBillingStepConfigEntity;
import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.billing.service.BillingStepConfigService;
import com.huashuo.billing.service.UsageEstimateService;
import com.huashuo.task.config.TaskCreditProperties;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 统一的预估实现：与 {@code TaskServiceImpl} 共享同一份 {@link #resolveCreditCost(String, Long)}，
 * 让前端展示金额与 {@code createTask} 实际预扣金额严格一致。
 */
@Service
public class BillingEstimateServiceImpl implements BillingEstimateService {

    private final BillingStepConfigService billingStepConfigService;
    private final TaskCreditProperties taskCreditProperties;
    private final UsageEstimateService usageEstimateService;
    private final CreditService creditService;

    public BillingEstimateServiceImpl(BillingStepConfigService billingStepConfigService,
                                      TaskCreditProperties taskCreditProperties,
                                      UsageEstimateService usageEstimateService,
                                      CreditService creditService) {
        this.billingStepConfigService = billingStepConfigService;
        this.taskCreditProperties = taskCreditProperties;
        this.usageEstimateService = usageEstimateService;
        this.creditService = creditService;
    }

    @Override
    public long resolveCreditCost(String taskType, Long creditCostOverride) {
        if (creditCostOverride != null) {
            return Math.max(0L, creditCostOverride);
        }
        String normalized = normalizeTaskType(taskType);
        OptionalLong fromSteps = billingStepConfigService.aggregateCreditCost(normalized);
        if (fromSteps.isPresent()) {
            return Math.max(0L, fromSteps.getAsLong());
        }
        return Math.max(0L, taskCreditProperties.costFor(normalized));
    }

    @Override
    public BillingEstimateResponse estimate(BillingEstimateRequest request) {
        String taskType = request == null ? null : request.taskType();
        String normalized = normalizeTaskType(taskType);

        OptionalLong fromSteps = StringUtils.hasText(normalized)
                ? billingStepConfigService.aggregateCreditCost(normalized)
                : OptionalLong.empty();
        long creditCost;
        String pricingSource;
        if (fromSteps.isPresent()) {
            creditCost = Math.max(0L, fromSteps.getAsLong());
            pricingSource = BillingEstimateResponse.SOURCE_BILLING_STEP_CONFIG;
        } else {
            creditCost = Math.max(0L, taskCreditProperties.costFor(normalized));
            pricingSource = BillingEstimateResponse.SOURCE_TASK_CREDIT_PROPERTIES;
        }

        // usageEstimateService 仅用来回填 provider / modelCode / usageUnit / 估算 usage 等元数据，
        // 它返回的 estimatedCreditCost 必须被 creditCost 覆盖（口径与 TaskServiceImpl.createTask 完全一致）。
        UsageEstimateResult metaEstimate = usageEstimateService.estimate(
                normalized,
                request == null ? null : request.modelCode(),
                null,
                creditCost
        );

        String resolvedUsageUnit = firstText(
                request == null ? null : request.usageUnit(),
                metaEstimate == null ? null : metaEstimate.usageUnit()
        );
        String resolvedModelCode = firstText(
                request == null ? null : request.modelCode(),
                metaEstimate == null ? null : metaEstimate.modelCode()
        );
        String resolvedProvider = metaEstimate == null ? null : metaEstimate.provider();

        List<BillingEstimateResponse.BillingEstimateStep> steps = buildSteps(normalized);

        Long balance = null;
        Boolean enoughBalance = null;
        Long ownerUserId = request == null ? null : request.ownerUserId();
        if (ownerUserId != null) {
            UserCreditAccountEntity account = creditService.ensureAccount(ownerUserId);
            balance = account == null || account.getBalance() == null ? 0L : account.getBalance();
            enoughBalance = balance >= creditCost;
        }

        return new BillingEstimateResponse(
                normalized,
                creditCost,
                resolvedUsageUnit,
                resolvedModelCode,
                resolvedProvider,
                pricingSource,
                balance,
                enoughBalance,
                steps
        );
    }

    private List<BillingEstimateResponse.BillingEstimateStep> buildSteps(String taskType) {
        if (!StringUtils.hasText(taskType)) {
            return List.of();
        }
        List<AiBillingStepConfigEntity> rows = billingStepConfigService.listEnabledSteps(taskType);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<BillingEstimateResponse.BillingEstimateStep> list = new ArrayList<>(rows.size());
        for (AiBillingStepConfigEntity row : rows) {
            long stepCost = row.getCreditCost() == null ? 0L : Math.max(0L, row.getCreditCost());
            boolean enabled = row.getEnabled() != null && row.getEnabled() == 1;
            list.add(new BillingEstimateResponse.BillingEstimateStep(
                    row.getStepName(),
                    row.getFunctionModule(),
                    stepCost,
                    enabled,
                    row.getUsageUnit(),
                    row.getModelCode(),
                    row.getProvider(),
                    row.getCostText()
            ));
        }
        return list;
    }

    private static String normalizeTaskType(String taskType) {
        return StringUtils.hasText(taskType) ? taskType.trim().toUpperCase() : null;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
