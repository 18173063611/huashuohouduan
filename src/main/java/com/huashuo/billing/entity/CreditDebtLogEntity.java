package com.huashuo.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分欠费记录：当 {@code CreditBillingService.settle} 补扣实际成本时账户余额不足，
 * 差额作为一条欠费写入本表，账户余额永不为负，后续充值后再做对账补扣。
 */
@Data
@TableName("credit_debt_log")
public class CreditDebtLogEntity {

    @TableId(value = "debt_id", type = IdType.AUTO)
    private Long debtId;

    private Long userId;

    private Long taskId;

    /** {@link com.huashuo.billing.model.CreditDebtType}。 */
    private String debtType;

    /** 应补扣总金额。 */
    private Long debtCredits;

    /** 已补扣金额。 */
    private Long paidCredits;

    /** {@link com.huashuo.billing.model.CreditDebtStatus}。 */
    private String status;

    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
