package com.huashuo.admin.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AdminProviderOpsTicketItem(
        Long ticketId,
        Long taskId,
        Long ownerUserId,
        String taskType,
        String taskStatus,
        String provider,
        String modelCode,
        String errorMessage,
        String providerTaskId,
        String providerStatus,
        String opsStatus,
        String priority,
        Long assigneeAdminId,
        String supplierTicketId,
        Boolean canDeleteProviderTask,
        String nextAction,
        String alertLevel,
        String alertReason,
        Long alertElapsedSeconds,
        Long alertTimeoutSeconds,
        LocalDateTime slaDeadlineAt,
        Boolean slaOverdue,
        String supplierResponse,
        String attachmentJson,
        String remark,
        String retryApprovalStatus,
        Long retryRequestedByAdminId,
        LocalDateTime retryRequestedAt,
        Long retryApprovedByAdminId,
        LocalDateTime retryApprovedAt,
        String retryApprovalRemark,
        Boolean manualRetryRequired,
        Boolean canManualRetry,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime closedAt,
        LocalDateTime taskCreatedAt,
        LocalDateTime taskUpdatedAt,
        List<AdminProviderOpsActionItem> actions
) {
}
