package com.huashuo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_credit_account")
/**
 * 用户积分账户快照：只保存当前余额和累计统计，所有变化原因以 user_credit_log 为准。
 */
public class UserCreditAccountEntity {

    @TableId(value = "credit_account_id", type = IdType.AUTO)
    private Long creditAccountId;

    private Long userId;

    private Long balance;

    private Long frozenBalance;

    private Long totalRecharged;

    private Long totalConsumed;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
