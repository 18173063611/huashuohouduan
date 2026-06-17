package com.huashuo.admin.vo;

import java.time.LocalDateTime;

public record AdminProviderOpsActionItem(
        Long actionId,
        Long ticketId,
        Long taskId,
        String actionType,
        String fromStatus,
        String toStatus,
        Long operatorAdminId,
        String supplierTicketId,
        String remark,
        String supplierResponse,
        String attachmentJson,
        String retryApprovalStatus,
        LocalDateTime createdAt
) {
}
