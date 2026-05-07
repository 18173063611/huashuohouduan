package com.huashuo.upload.vo;

import java.time.LocalDateTime;

public record UploadedFileItem(
        Long fileId,
        Long projectId,
        Long ownerUserId,
        String originalFileName,
        String storedFileName,
        String filePath,
        String previewUrl,
        String mimeType,
        Long fileSize,
        LocalDateTime createdAt
) {
}
