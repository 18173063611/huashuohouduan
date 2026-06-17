package com.huashuo.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("provider_ops_ticket")
public class ProviderOpsTicketEntity {

    @TableId(value = "ticket_id", type = IdType.AUTO)
    private Long ticketId;

    private Long taskId;

    private Long ownerUserId;

    private String taskType;

    private String provider;

    private String providerTaskId;

    private String providerStatus;

    private String status;

    private String priority;

    private Long assigneeAdminId;

    private String supplierTicketId;

    private Integer canDeleteProviderTask;

    private String nextAction;

    private String alertLevel;

    private String alertReason;

    private Long alertElapsedSeconds;

    private Long alertTimeoutSeconds;

    private LocalDateTime slaDeadlineAt;

    private String supplierResponse;

    private String attachmentJson;

    private String retryApprovalStatus;

    private Long retryRequestedByAdminId;

    private LocalDateTime retryRequestedAt;

    private Long retryApprovedByAdminId;

    private LocalDateTime retryApprovedAt;

    private String retryApprovalRemark;

    private String lastRemark;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
