package com.huashuo.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台「AI 计费配置」步骤行展示对象。映射 {@code ai_billing_step_config} 实体，但把 {@code enabled} 暴露为布尔。
 */
public record AdminBillingStepItem(
        Long stepId,
        String taskType,
        String functionModule,
        String stepName,
        String provider,
        String modelCode,
        String usageUnit,
        String callCount,
        String costText,
        Long creditCost,
        Boolean enabled,
        Integer sortOrder,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
