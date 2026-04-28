package com.huashuo.upload.vo;

import java.time.LocalDateTime;

public record FwxUploadedFileItem(
        Long fileId,
        Long projectId,
        String originalFileName,
        String storedFileName,
        String filePath,
        String previewUrl,
        String mimeType,
        Long fileSize,
        LocalDateTime createdAt
) {
}
