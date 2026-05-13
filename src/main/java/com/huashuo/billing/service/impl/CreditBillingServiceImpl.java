package com.huashuo.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.billing.entity.AiModelPriceEntity;
import com.huashuo.billing.entity.AiUsageLogEntity;
import com.huashuo.billing.mapper.AiModelPriceMapper;
import com.huashuo.billing.mapper.AiUsageLogMapper;
import com.huashuo.billing.model.SettlementStatus;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.user.service.CreditChangeResult;
import com.huashuo.user.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
public class CreditBillingServiceImpl implements CreditBillingService {

    private final CreditService creditService;
    private final TaskMapper taskMapper;
    private final AiModelPriceMapper aiModelPriceMapper;
    private final AiUsageLogMapper aiUsageLogMapper;

    public CreditBillingServiceImpl(CreditService creditService, TaskMapper taskMapper,
                                    AiModelPriceMapper aiModelPriceMapper, AiUsageLogMapper aiUsageLogMapper) {
        this.creditService = creditService;
        this.taskMapper = taskMapper;
        this.aiModelPriceMapper = aiModelPriceMapper;
        this.aiUsageLogMapper = aiUsageLogMapper;
    }

    @Override
    @Transactional
    public CreditChangeResult precharge(Long userId, Long taskId, UsageEstimateResult estimate,
                                        String idempotencyKey, String remark) {
        long amount = estimate == null ? 0L : Math.max(0L, estimate.estimatedCreditCost());
        if (amount <= 0) {
            return null;
        }
        return creditService.consumeForTask(userId, taskId, estimate.modelCode(), amount, idempotencyKey, remark);
    }

    @Override
    @Transactional
    public void settle(Long taskId, UsageActualResult actualUsage) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || actualUsage == null || task.getOwnerUserId() == null) {
            return;
        }
        long estimatedCost = task.getEstimatedCreditCost() == null ? safe(task.getCreditCost()) : safe(task.getEstimatedCreditCost());
        long actualCost = actualUsage.actualCreditCost() == null
                ? calculateActualCost(task, actualUsage)
                : Math.max(0L, actualUsage.actualCreditCost());

        insertUsageLog(task, actualUsage, estimatedCost, actualCost);
        task.setProvider(firstText(actualUsage.provider(), task.getProvider()));
        task.setModelCode(firstText(actualUsage.modelCode(), task.getModelCode()));
        task.setUsageUnit(firstText(actualUsage.usageUnit(), task.getUsageUnit()));
        task.setActualUsage(resolveActualUsage(actualUsage));
        task.setActualCreditCost(actualCost);
        task.setUpdatedAt(LocalDateTime.now());

        long delta = actualCost - estimatedCost;
        if (delta < 0) {
            long refund = Math.abs(delta);
            creditService.refundForTask(
                    task.getOwnerUserId(),
                    task.getTaskId(),
                    task.getModelCode(),
                    refund,
                    "AI_SETTLE_REFUND:" + task.getTaskId(),
                    "AI 任务按实际用量结算退差额"
            );
            task.setSettlementStatus(actualCost == 0 ? SettlementStatus.REFUNDED : SettlementStatus.PARTIAL_REFUNDED);
        } else if (delta > 0) {
            try {
                creditService.consumeForTask(
                        task.getOwnerUserId(),
                        task.getTaskId(),
                        task.getModelCode(),
                        delta,
                        "AI_SETTLE_EXTRA:" + task.getTaskId(),
                        "AI 任务按实际用量结算补扣"
                );
                task.setSettlementStatus(SettlementStatus.SETTLED);
            } catch (Exception ignored) {
                task.setSettlementStatus(SettlementStatus.SETTLE_FAILED);
            }
        } else {
            task.setSettlementStatus(SettlementStatus.SETTLED);
        }
        taskMapper.updateById(task);
    }

    private long calculateActualCost(TaskEntity task, UsageActualResult actualUsage) {
        AiModelPriceEntity price = findPrice(task, actualUsage);
        if (price == null) {
            return safe(task.getCreditCost());
        }
        String usageUnit = normalize(firstText(actualUsage.usageUnit(), price.getUsageUnit()));
        if (UsageUnit.TOKEN.equals(usageUnit)) {
            BigDecimal input = BigDecimal.valueOf(safeInt(actualUsage.promptTokens()))
                    .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP)
                    .multiply(nonNull(price.getInputCreditPer1k()));
            BigDecimal output = BigDecimal.valueOf(safeInt(actualUsage.completionTokens()))
                    .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP)
                    .multiply(nonNull(price.getOutputCreditPer1k()));
            return input.add(output).setScale(0, RoundingMode.CEILING).longValue();
        }
        BigDecimal amount = resolveActualUsage(actualUsage);
        BigDecimal cost = amount.multiply(nonNull(price.getUnitCreditPrice()));
        if (UsageUnit.CHAR.equals(usageUnit)) {
            cost = amount.divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP)
                    .multiply(nonNull(price.getUnitCreditPrice()));
        }
        return cost.setScale(0, RoundingMode.CEILING).longValue();
    }

    private AiModelPriceEntity findPrice(TaskEntity task, UsageActualResult actualUsage) {
        LambdaQueryWrapper<AiModelPriceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiModelPriceEntity::getTaskType, task.getTaskType())
                .eq(AiModelPriceEntity::getEnabled, 1)
                .eq(AiModelPriceEntity::getDeleted, 0);
        String modelCode = firstText(actualUsage.modelCode(), task.getModelCode());
        if (StringUtils.hasText(modelCode)) {
            wrapper.eq(AiModelPriceEntity::getModelCode, modelCode.trim());
        }
        wrapper.last("limit 1");
        return aiModelPriceMapper.selectOne(wrapper);
    }

    private void insertUsageLog(TaskEntity task, UsageActualResult actualUsage, long estimatedCost, long actualCost) {
        AiUsageLogEntity log = new AiUsageLogEntity();
        log.setTaskId(task.getTaskId());
        log.setUserId(task.getOwnerUserId());
        log.setTaskType(task.getTaskType());
        log.setProvider(firstText(actualUsage.provider(), task.getProvider()));
        log.setModelCode(firstText(actualUsage.modelCode(), task.getModelCode()));
        log.setUsageUnit(firstText(actualUsage.usageUnit(), task.getUsageUnit(), UsageUnit.TASK));
        log.setPromptTokens(safeInt(actualUsage.promptTokens()));
        log.setCompletionTokens(safeInt(actualUsage.completionTokens()));
        log.setTotalTokens(safeInt(actualUsage.totalTokens()));
        log.setCharacterCount(safeInt(actualUsage.characterCount()));
        log.setImageCount(safeInt(actualUsage.imageCount()));
        log.setDurationSeconds(nonNull(actualUsage.durationSeconds()));
        log.setProviderCredits(nonNull(actualUsage.providerCredits()));
        log.setEstimatedCreditCost(estimatedCost);
        log.setActualCreditCost(actualCost);
        log.setRawUsageJson(actualUsage.rawUsageJson());
        log.setCreatedAt(LocalDateTime.now());
        aiUsageLogMapper.insert(log);
    }

    private BigDecimal resolveActualUsage(UsageActualResult actualUsage) {
        String unit = normalize(actualUsage.usageUnit());
        if (UsageUnit.TOKEN.equals(unit)) {
            return BigDecimal.valueOf(safeInt(actualUsage.totalTokens()));
        }
        if (UsageUnit.CHAR.equals(unit)) {
            return BigDecimal.valueOf(safeInt(actualUsage.characterCount()));
        }
        if (UsageUnit.IMAGE.equals(unit)) {
            return BigDecimal.valueOf(safeInt(actualUsage.imageCount()));
        }
        if (UsageUnit.SECOND.equals(unit)) {
            return nonNull(actualUsage.durationSeconds());
        }
        if (UsageUnit.PROVIDER_CREDIT.equals(unit)) {
            return nonNull(actualUsage.providerCredits());
        }
        return BigDecimal.ONE;
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
