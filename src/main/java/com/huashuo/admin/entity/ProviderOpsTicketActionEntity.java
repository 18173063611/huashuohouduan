package com.huashuo.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("provider_ops_ticket_action")
public class ProviderOpsTicketActionEntity {

    @TableId(value = "action_id", type = IdType.AUTO)
    private Long actionId;

    private Long ticketId;

    private Long taskId;

    private String actionType;

    private String fromStatus;

    private String toStatus;

    private Long operatorAdminId;

    private String supplierTicketId;

    private String remark;

    private String supplierResponse;

    private String attachmentJson;

    private String retryApprovalStatus;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
