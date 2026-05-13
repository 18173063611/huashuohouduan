package com.huashuo.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_model_price")
public class AiModelPriceEntity {

    @TableId(value = "price_id", type = IdType.AUTO)
    private Long priceId;

    private String provider;

    private String modelCode;

    private String modelName;

    private String taskType;

    private String usageUnit;

    @TableField("input_credit_per_1k")
    private BigDecimal inputCreditPer1k;

    @TableField("output_credit_per_1k")
    private BigDecimal outputCreditPer1k;

    private BigDecimal unitCreditPrice;

    private BigDecimal estimateOutputRatio;

    private BigDecimal estimateBufferRatio;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
