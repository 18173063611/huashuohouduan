package com.huashuo.avatar.vo;

import java.time.LocalDateTime;

public record AvatarItem(
        Long avatarId,
        Long projectId,
        Long taskId,
        Long assetId,
        String avatarName,
        String sourceType,
        String prompt,
        String referenceAssetIds,
        String previewUrl,
        String metadataJson,
        Boolean defaultAvatar,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
