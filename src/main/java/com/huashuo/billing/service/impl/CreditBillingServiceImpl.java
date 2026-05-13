package com.huashuo.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.entity.AiModelPriceEntity;
import com.huashuo.billing.entity.AiUsageLogEntity;
import com.huashuo.billing.mapper.AiModelPriceMapper;
import com.huashuo.billing.mapper.AiUsageLogMapper;
import com.huashuo.billing.model.SettlementStatus;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.billing.model.UsagePhase;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.user.service.CreditChangeResult;
import com.huashuo.user.service.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CreditBillingServiceImpl implements CreditBillingService {

    private static final Logger log = LoggerFactory.getLogger(CreditBillingServiceImpl.class);

    private final CreditService creditService;
    private final TaskMapper taskMapper;
    private final AiModelPriceMapper aiModelPriceMapper;
    private final AiUsageLogMapper aiUsageLogMapper;
    private final ObjectMapper objectMapper;

    public CreditBillingServiceImpl(CreditService creditService, TaskMapper taskMapper,
                                    AiModelPriceMapper aiModelPriceMapper, AiUsageLogMapper aiUsageLogMapper,
                                    ObjectMapper objectMapper) {
        this.creditService = creditService;
        this.taskMapper = taskMapper;
        this.aiModelPriceMapper = aiModelPriceMapper;
        this.aiUsageLogMapper = aiUsageLogMapper;
        this.objectMapper = objectMapper;
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
    public Long recordEstimate(Long userId, Long taskId, String taskType, UsageEstimateResult estimate) {
        if (taskId == null) {
            return null;
        }
        long estimated = estimate == null ? 0L : Math.max(0L, estimate.estimatedCreditCost());
        AiUsageLogEntity log = new AiUsageLogEntity();
        log.setTaskId(taskId);
        log.setUserId(userId);
        log.setTaskType(StringUtils.hasText(taskType) ? taskType.trim() : null);
        log.setProvider(estimate == null ? null : firstText(estimate.provider()));
        log.setModelCode(estimate == null ? null : firstText(estimate.modelCode()));
        log.setUsageUnit(estimate == null
                ? UsageUnit.TASK
                : firstText(estimate.usageUnit(), UsageUnit.TASK));
        log.setUsagePhase(UsagePhase.ESTIMATE);
        log.setPromptTokens(estimate == null ? 0 : safeInt(estimate.estimatedPromptTokens()));
        log.setCompletionTokens(estimate == null ? 0 : safeInt(estimate.estimatedCompletionTokens()));
        log.setTotalTokens(log.getPromptTokens() + log.getCompletionTokens());
        log.setCharacterCount(0);
        log.setImageCount(0);
        log.setDurationSeconds(BigDecimal.ZERO);
        log.setProviderCredits(BigDecimal.ZERO);
        log.setEstimatedCreditCost(estimated);
        log.setActualCreditCost(0L);
        log.setRawUsageJson(null);
        log.setCreatedAt(LocalDateTime.now());
        aiUsageLogMapper.insert(log);
        return log.getUsageId();
    }

    @Override
    @Transactional
    public Long recordActual(Long taskId, UsageActualResult actualUsage) {
        if (taskId == null) {
            return null;
        }
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        UsageActualResult safeUsage = actualUsage == null ? emptyActual() : actualUsage;
        long estimatedCost = task.getEstimatedCreditCost() == null
                ? safe(task.getCreditCost())
                : safe(task.getEstimatedCreditCost());

        AiUsageLogEntity log = new AiUsageLogEntity();
        log.setTaskId(taskId);
        log.setUserId(task.getOwnerUserId());
        log.setTaskType(task.getTaskType());
        log.setProvider(firstText(safeUsage.provider(), task.getProvider()));
        log.setModelCode(firstText(safeUsage.modelCode(), task.getModelCode()));
        log.setUsageUnit(firstText(safeUsage.usageUnit(), task.getUsageUnit(), UsageUnit.TASK));
        log.setUsagePhase(UsagePhase.ACTUAL);
        log.setPromptTokens(safeInt(safeUsage.promptTokens()));
        log.setCompletionTokens(safeInt(safeUsage.completionTokens()));
        log.setTotalTokens(safeInt(safeUsage.totalTokens()));
        log.setCharacterCount(safeInt(safeUsage.characterCount()));
        log.setImageCount(safeInt(safeUsage.imageCount()));
        log.setDurationSeconds(nonNull(safeUsage.durationSeconds()));
        log.setProviderCredits(nonNull(safeUsage.providerCredits()));
        log.setEstimatedCreditCost(estimatedCost);
        // 占位行：未做积分补扣/退款，actual_credit_cost 保持 0，由后续真正 settle 时再写一行 ACTUAL 覆盖。
        log.setActualCreditCost(0L);
        log.setRawUsageJson(safeUsage.rawUsageJson());
        log.setCreatedAt(LocalDateTime.now());
        aiUsageLogMapper.insert(log);

        // 同步 task 表元信息：只有 actualUsage 中明确给出才覆盖 task 行，避免冲掉旧值。
        boolean taskDirty = false;
        BigDecimal usageAmount = resolveActualUsage(safeUsage);
        if (usageAmount != null && usageAmount.signum() > 0) {
            task.setActualUsage(usageAmount);
            taskDirty = true;
        }
        if (StringUtils.hasText(safeUsage.provider())) {
            task.setProvider(safeUsage.provider().trim());
            taskDirty = true;
        }
        if (StringUtils.hasText(safeUsage.modelCode())) {
            task.setModelCode(safeUsage.modelCode().trim());
            taskDirty = true;
        }
        if (StringUtils.hasText(safeUsage.usageUnit())) {
            task.setUsageUnit(safeUsage.usageUnit().trim());
            taskDirty = true;
        }
        if (taskDirty) {
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
        return log.getUsageId();
    }

    @Override
    @Transactional
    public Long recordConsumeWithoutRefund(Long taskId, String failReason) {
        if (taskId == null) {
            return null;
        }
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        // 幂等：任务已经走过终态结算就 short-circuit，避免被 failTask 兜底再次冲掉真正的 settle 结果。
        String current = task.getSettlementStatus();
        if (SettlementStatus.SETTLED.equals(current)
                || SettlementStatus.REFUNDED.equals(current)
                || SettlementStatus.PARTIAL_REFUNDED.equals(current)) {
            return null;
        }
        long estimatedCost = task.getEstimatedCreditCost() == null
                ? safe(task.getCreditCost())
                : safe(task.getEstimatedCreditCost());
        if (estimatedCost <= 0) {
            // 没有预扣的任务（PRECHARGED 之外的 NONE / 0 元任务）直接落 SETTLED，无需写 usage_log ACTUAL 占位行。
            task.setActualCreditCost(0L);
            task.setSettlementStatus(SettlementStatus.SETTLED);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            return null;
        }

        AiUsageLogEntity logEntity = new AiUsageLogEntity();
        logEntity.setTaskId(taskId);
        logEntity.setUserId(task.getOwnerUserId());
        logEntity.setTaskType(task.getTaskType());
        logEntity.setProvider(task.getProvider());
        logEntity.setModelCode(task.getModelCode());
        logEntity.setUsageUnit(firstText(task.getUsageUnit(), UsageUnit.TASK));
        logEntity.setUsagePhase(UsagePhase.ACTUAL);
        logEntity.setPromptTokens(0);
        logEntity.setCompletionTokens(0);
        logEntity.setTotalTokens(0);
        logEntity.setCharacterCount(0);
        logEntity.setImageCount(0);
        logEntity.setDurationSeconds(BigDecimal.ZERO);
        logEntity.setProviderCredits(BigDecimal.ZERO);
        logEntity.setEstimatedCreditCost(estimatedCost);
        // 第三方已受理：预扣视为实际成本被消费掉，不再退款。
        logEntity.setActualCreditCost(estimatedCost);
        logEntity.setRawUsageJson(buildFailureRawUsageJson(failReason));
        logEntity.setCreatedAt(LocalDateTime.now());
        aiUsageLogMapper.insert(logEntity);

        task.setActualCreditCost(estimatedCost);
        task.setSettlementStatus(SettlementStatus.SETTLED);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return logEntity.getUsageId();
    }

    /**
     * 失败但不退款场景的 raw_usage_json：固定结构以便对账脚本与运营 SQL 直接 JSON_EXTRACT。
     */
    private String buildFailureRawUsageJson(String failReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("failReason", StringUtils.hasText(failReason) ? failReason.trim() : null);
        payload.put("refundCredits", false);
        payload.put("thirdPartyAccepted", true);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("serialize failure raw_usage_json failed, fallback to plain text. failReason={}", failReason, ex);
            return "{\"failReason\":null,\"refundCredits\":false,\"thirdPartyAccepted\":true}";
        }
    }

    private UsageActualResult emptyActual() {
        return new UsageActualResult(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    @Transactional
    public void settle(Long taskId, UsageActualResult actualUsage) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || actualUsage == null || task.getOwnerUserId() == null) {
            return;
        }
        // 幂等：终态任务直接跳过，避免重复写 ACTUAL usage_log / 多次补扣或退款。底层 creditService.consumeForTask /
        // refundForTask 自身也基于 idempotencyKey 去重，但仍会浪费一次查询，且会写出重复 ACTUAL 行干扰对账。
        String current = task.getSettlementStatus();
        if (SettlementStatus.SETTLED.equals(current)
                || SettlementStatus.REFUNDED.equals(current)
                || SettlementStatus.PARTIAL_REFUNDED.equals(current)) {
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
        log.setUsagePhase(UsagePhase.ACTUAL);
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
