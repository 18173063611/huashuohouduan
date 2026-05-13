package com.huashuo.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_usage_log")
public class AiUsageLogEntity {

    @TableId(value = "usage_id", type = IdType.AUTO)
    private Long usageId;

    private Long taskId;

    private Long userId;

    private String taskType;

    private String provider;

    private String modelCode;

    private String usageUnit;

    /**
     * 用量记录阶段：ESTIMATE = 任务创建时按计费配置写入的估算占位行；ACTUAL = 任务结束后写入的实际结算/占位行。
     * 同一 taskId 通常会有 1 条 ESTIMATE + 0~1 条 ACTUAL。
     */
    private String usagePhase;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer characterCount;

    private Integer imageCount;

    private BigDecimal durationSeconds;

    private BigDecimal providerCredits;

    private Long estimatedCreditCost;

    private Long actualCreditCost;

    private String rawUsageJson;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
