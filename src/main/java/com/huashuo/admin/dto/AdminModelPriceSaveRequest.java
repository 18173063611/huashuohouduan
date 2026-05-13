package com.huashuo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 后台「AI 计费配置 - 模型单价」保存请求：新增 / 编辑共用。
 */
public record AdminModelPriceSaveRequest(
        @NotBlank
        @Size(max = 50)
        String provider,

        @NotBlank
        @Size(max = 100)
        String modelCode,

        @Size(max = 120)
        String modelName,

        @Size(max = 50)
        String taskType,

        @Size(max = 30)
        String usageUnit,

        BigDecimal inputCreditPer1k,

        BigDecimal outputCreditPer1k,

        BigDecimal unitCreditPrice,

        BigDecimal estimateOutputRatio,

        BigDecimal estimateBufferRatio,

        Boolean enabled
) {
}
