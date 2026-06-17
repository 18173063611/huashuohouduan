package com.huashuo.support.vo;

import java.time.LocalDateTime;
import java.util.List;

public record CustomerFeedbackItem(
        Long feedbackId,
        Long ownerUserId,
        String username,
        String displayName,
        String category,
        String priority,
        String status,
        String title,
        String content,
        String contact,
        Long relatedTaskId,
        Long projectId,
        String pageUrl,
        String sourcePath,
        String userAgent,
        List<Long> attachmentFileIds,
        List<CustomerFeedbackAttachmentItem> attachments,
        String adminReply,
        String adminNote,
        Long assigneeAdminId,
        LocalDateTime firstResponseAt,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
