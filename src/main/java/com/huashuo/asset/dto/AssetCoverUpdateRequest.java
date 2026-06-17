package com.huashuo.asset.dto;

public record AssetCoverUpdateRequest(
        String thumbnailUrl,
        String metadataJson
) {
}
