package com.huashuo.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model_config")
/**
 * AI 模型后台配置：只管理模型展示、成本和能力参数，不保存任何 API Key。
 */
public class AiModelConfigEntity {

    @TableId(value = "model_id", type = IdType.AUTO)
    private Long modelId;

    private String modelCode;

    private String modelName;

    private String modelType;

    private String provider;

    private String providerModel;

    private Long creditCost;

    private Integer enabled;

    private Integer defaultModel;

    private String capabilityJson;

    private String defaultParamsJson;

    private Integer rateLimitPerMinute;

    private Integer concurrencyLimit;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
