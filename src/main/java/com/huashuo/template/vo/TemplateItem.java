package com.huashuo.template.vo;

import java.time.LocalDateTime;
import java.util.List;

public record TemplateItem(
        Long templateId,
        Long ownerUserId,
        Long createdByUserId,
        String visibility,
        String status,
        LocalDateTime publishedAt,
        Integer versionNo,
        String title,
        String description,
        Long coverAssetId,
        String tags,
        String metadataJson,
        List<TemplateAssetRef> assets,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record TemplateAssetRef(Long assetId, String role) {
    }
}

