package com.huashuo.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_operation_log")
/**
 * 管理员操作日志：关键后台动作追加记录，便于误操作追溯。
 */
public class AdminOperationLogEntity {

    @TableId(value = "operation_id", type = IdType.AUTO)
    private Long operationId;

    private Long adminUserId;

    private String operationType;

    private String targetType;

    private Long targetId;

    private String beforeJson;

    private String afterJson;

    private String ip;

    private String traceId;

    private LocalDateTime createdAt;
}
