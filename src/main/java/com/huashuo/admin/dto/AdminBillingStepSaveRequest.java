package com.huashuo.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 后台「AI 计费配置」保存请求：新增 / 编辑共用。
 * <p>仅暴露允许后台修改的字段；step_id / created_at / updated_at / deleted 不在此处。</p>
 */
public record AdminBillingStepSaveRequest(
        @NotBlank
        @Size(max = 50)
        String taskType,

        @Size(max = 80)
        String functionModule,

        @NotBlank
        @Size(max = 120)
        String stepName,

        @Size(max = 50)
        String provider,

        @Size(max = 100)
        String modelCode,

        @Size(max = 30)
        String usageUnit,

        @Size(max = 60)
        String callCount,

        @Size(max = 200)
        String costText,

        @Min(0)
        Long creditCost,

        Boolean enabled,

        Integer sortOrder,

        @Size(max = 500)
        String remark
) {
}
