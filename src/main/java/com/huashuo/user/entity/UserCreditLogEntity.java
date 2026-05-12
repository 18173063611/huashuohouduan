package com.huashuo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_credit_log")
/**
 * 积分流水：积分账户任何变动都追加一条流水，用于后台审计和后续成本对账。
 */
public class UserCreditLogEntity {

    @TableId(value = "credit_log_id", type = IdType.AUTO)
    private Long creditLogId;

    private Long userId;

    private String changeType;

    private Long changeAmount;

    private Long beforeBalance;

    private Long afterBalance;

    private Long relatedTaskId;

    private String modelCode;

    private Long operatorAdminId;

    private String idempotencyKey;

    private String remark;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
