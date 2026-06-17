package com.huashuo.support.vo;

import java.time.LocalDateTime;

public record CustomerFeedbackAttachmentItem(
        Long fileId,
        String originalFileName,
        String previewUrl,
        String mimeType,
        Long fileSize,
        LocalDateTime createdAt
) {
}
