package com.huashuo.asset.dto;

public record AssetContentUpdateRequest(
        String fileName,
        String content,
        String metadataJson
) {
}
