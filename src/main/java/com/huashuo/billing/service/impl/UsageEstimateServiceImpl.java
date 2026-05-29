package com.huashuo.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.entity.AiModelPriceEntity;
import com.huashuo.billing.mapper.AiModelPriceMapper;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.UsageEstimateService;
import com.huashuo.task.enums.TaskTypeCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service
public class UsageEstimateServiceImpl implements UsageEstimateService {

    private final AiModelPriceMapper aiModelPriceMapper;
    private final ObjectMapper objectMapper;

    public UsageEstimateServiceImpl(AiModelPriceMapper aiModelPriceMapper, ObjectMapper objectMapper) {
        this.aiModelPriceMapper = aiModelPriceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public UsageEstimateResult estimate(String taskType, String modelCode, String inputJson, Long fixedCreditFallback) {
        String normalizedTaskType = normalize(taskType);
        AiModelPriceEntity price = findPrice(normalizedTaskType, modelCode);
        if (price == null) {
            long fallback = fixedCreditFallback == null ? 0L : Math.max(0L, fixedCreditFallback);
            return new UsageEstimateResult(null, trimToNull(modelCode), UsageUnit.TASK, BigDecimal.ONE, null, null, fallback);
        }

        String usageUnit = normalize(price.getUsageUnit());
        Map<String, Object> input = parseInput(inputJson);
        if (UsageUnit.TOKEN.equals(usageUnit)) {
            int promptTokens = estimatePromptTokens(inputJson);
            int completionTokens = estimateCompletionTokens(normalizedTaskType, promptTokens, price);
            long cost = fallbackIfZero(tokenCost(promptTokens, completionTokens, price), fixedCreditFallback);
            return new UsageEstimateResult(price.getProvider(), price.getModelCode(), usageUnit,
                    BigDecimal.valueOf((long) promptTokens + completionTokens), promptTokens, completionTokens, cost);
        }
        if (UsageUnit.CHAR.equals(usageUnit)) {
            int chars = estimateCharacterCount(input);
            long cost = fallbackIfZero(unitCost(BigDecimal.valueOf(chars), price), fixedCreditFallback);
            return new UsageEstimateResult(price.getProvider(), price.getModelCode(), usageUnit,
                    BigDecimal.valueOf(chars), null, null, cost);
        }
        if (UsageUnit.IMAGE.equals(usageUnit)) {
            Number count = input.get("imageCount") instanceof Number n ? n : 1;
            BigDecimal amount = BigDecimal.valueOf(Math.max(1L, count.longValue()));
            return new UsageEstimateResult(price.getProvider(), price.getModelCode(), usageUnit,
                    amount, null, null, fallbackIfZero(unitCost(amount, price), fixedCreditFallback));
        }
        return new UsageEstimateResult(price.getProvider(), price.getModelCode(), usageUnit,
                BigDecimal.ONE, null, null, fallbackIfZero(unitCost(BigDecimal.ONE, price), fixedCreditFallback));
    }

    private AiModelPriceEntity findPrice(String taskType, String modelCode) {
        LambdaQueryWrapper<AiModelPriceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiModelPriceEntity::getTaskType, taskType)
                .eq(AiModelPriceEntity::getEnabled, 1)
                .eq(AiModelPriceEntity::getDeleted, 0);
        if (StringUtils.hasText(modelCode)) {
            wrapper.eq(AiModelPriceEntity::getModelCode, modelCode.trim());
        }
        wrapper.last("limit 1");
        AiModelPriceEntity price = aiModelPriceMapper.selectOne(wrapper);
        if (price != null || StringUtils.hasText(modelCode)) {
            return price;
        }
        return aiModelPriceMapper.selectOne(new LambdaQueryWrapper<AiModelPriceEntity>()
                .eq(AiModelPriceEntity::getTaskType, taskType)
                .eq(AiModelPriceEntity::getEnabled, 1)
                .eq(AiModelPriceEntity::getDeleted, 0)
                .last("limit 1"));
    }

    private int estimatePromptTokens(String inputJson) {
        int chars = StringUtils.hasText(inputJson) ? inputJson.trim().length() : 0;
        return Math.max(1, BigDecimal.valueOf(chars)
                .divide(BigDecimal.valueOf(1.5), 0, RoundingMode.CEILING)
                .intValue());
    }

    private int estimateCompletionTokens(String taskType, int promptTokens, AiModelPriceEntity price) {
        BigDecimal ratio = price.getEstimateOutputRatio() == null ? defaultOutputRatio(taskType) : price.getEstimateOutputRatio();
        int estimated = BigDecimal.valueOf(promptTokens).multiply(ratio).setScale(0, RoundingMode.CEILING).intValue();
        if (TaskTypeCode.SCRIPT_REWRITE.equals(taskType)) {
            return Math.max(200, estimated);
        }
        if (TaskTypeCode.STORYBOARD_GENERATE.equals(taskType)) {
            return Math.max(300, estimated);
        }
        return Math.max(100, estimated);
    }

    private BigDecimal defaultOutputRatio(String taskType) {
        if (TaskTypeCode.SCRIPT_REWRITE.equals(taskType)) {
            return BigDecimal.valueOf(1.2);
        }
        if (TaskTypeCode.STORYBOARD_GENERATE.equals(taskType)) {
            return BigDecimal.valueOf(1.5);
        }
        return BigDecimal.ONE;
    }

    private long tokenCost(int promptTokens, int completionTokens, AiModelPriceEntity price) {
        BigDecimal input = BigDecimal.valueOf(promptTokens)
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP)
                .multiply(nonNull(price.getInputCreditPer1k()));
        BigDecimal output = BigDecimal.valueOf(completionTokens)
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP)
                .multiply(nonNull(price.getOutputCreditPer1k()));
        return withBuffer(input.add(output), price).setScale(0, RoundingMode.CEILING).longValue();
    }

    private long unitCost(BigDecimal amount, AiModelPriceEntity price) {
        BigDecimal cost = amount.multiply(nonNull(price.getUnitCreditPrice()));
        if (UsageUnit.CHAR.equals(normalize(price.getUsageUnit()))) {
            cost = amount.divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP)
                    .multiply(nonNull(price.getUnitCreditPrice()));
        }
        return withBuffer(cost, price).setScale(0, RoundingMode.CEILING).longValue();
    }

    private BigDecimal withBuffer(BigDecimal value, AiModelPriceEntity price) {
        BigDecimal buffer = price.getEstimateBufferRatio() == null ? BigDecimal.ONE : price.getEstimateBufferRatio();
        return value.multiply(buffer);
    }

    private long fallbackIfZero(long value, Long fixedCreditFallback) {
        if (value > 0 || fixedCreditFallback == null) {
            return value;
        }
        return Math.max(0L, fixedCreditFallback);
    }

    private Map<String, Object> parseInput(String inputJson) {
        if (!StringUtils.hasText(inputJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(inputJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private int estimateCharacterCount(Map<String, Object> input) {
        Object explicitLength = input.get("inputTextLength");
        if (explicitLength == null) {
            explicitLength = input.get("textLength");
        }
        if (explicitLength instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        String explicitText = explicitLength == null ? null : String.valueOf(explicitLength).trim();
        if (StringUtils.hasText(explicitText)) {
            try {
                return Math.max(0, Integer.parseInt(explicitText));
            } catch (NumberFormatException ignored) {
                // Fall through to concrete text length.
            }
        }
        return textOf(input).length();
    }

    private String textOf(Map<String, Object> input) {
        Object text = input.get("text");
        if (text == null) {
            text = input.get("prompt");
        }
        return text == null ? "" : String.valueOf(text);
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
