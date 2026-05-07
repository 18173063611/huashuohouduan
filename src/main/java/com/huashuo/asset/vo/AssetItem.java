package com.huashuo.asset.vo;

import java.time.LocalDateTime;

public record AssetItem(
        Long assetId,
        Long ownerUserId,
        Long createdByUserId,
        Long projectId,
        Long taskId,
        String assetType,
        String kind,
        String visibility,
        String status,
        LocalDateTime publishedAt,
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
