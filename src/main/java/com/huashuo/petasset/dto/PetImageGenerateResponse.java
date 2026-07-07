package com.huashuo.petasset.dto;

import com.huashuo.asset.vo.AssetItem;

import java.util.List;

public record PetImageGenerateResponse(
        List<Long> assetIds,
        List<String> previewUrls,
        List<String> remoteImageUrls,
        List<AssetItem> assets
) {
}
