package com.huashuo.asset.dto;

public record CarModelBundleUpdateRequest(
        String fileName,
        String contentJson,
        String metadataJson
) {
}
