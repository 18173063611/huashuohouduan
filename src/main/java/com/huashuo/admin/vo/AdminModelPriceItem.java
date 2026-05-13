package com.huashuo.admin.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 后台「AI 计费配置 - 模型单价」行展示对象。映射 {@code ai_model_price} 实体。
 */
public record AdminModelPriceItem(
        Long priceId,
        String provider,
        String modelCode,
        String modelName,
        String taskType,
        String usageUnit,
        BigDecimal inputCreditPer1k,
        BigDecimal outputCreditPer1k,
        BigDecimal unitCreditPrice,
        BigDecimal estimateOutputRatio,
        BigDecimal estimateBufferRatio,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
