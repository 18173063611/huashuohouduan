package com.huashuo.admin.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AdminTaskProviderOps(
        Long ticketId,
        String providerTaskId,
        String providerStatus,
        Long elapsedSeconds,
        Long timeoutSeconds,
        Boolean canDeleteProviderTask,
        String nextAction,
        String alertLevel,
        String alertReason,
        String alertedAt,
        String opsStatus,
        String priority,
        Long assigneeAdminId,
        String supplierTicketId,
        String remark,
        String supplierResponse,
        String attachmentJson,
        Long operatorAdminId,
        String updatedAt,
        LocalDateTime slaDeadlineAt,
        String retryApprovalStatus,
        Long retryRequestedByAdminId,
        LocalDateTime retryRequestedAt,
        Long retryApprovedByAdminId,
        LocalDateTime retryApprovedAt,
        String retryApprovalRemark,
        Boolean manualRetryRequired,
        Boolean canManualRetry,
        List<AdminProviderOpsActionItem> actions
) {
}
