package com.huashuo.asset.vo;

import java.time.LocalDateTime;

public record AssetItem(
        Long assetId,
        Long ownerUserId,
        Long projectId,
        Long taskId,
        String assetType,
        String fileName,
        String filePath,
        String fileUrl,
        String thumbnailUrl,
        String mimeType,
        Long fileSize,
        String sourceType,
        String metadataJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
