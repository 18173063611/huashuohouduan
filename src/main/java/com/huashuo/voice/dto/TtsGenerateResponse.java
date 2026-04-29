package com.huashuo.voice.dto;

import com.huashuo.asset.vo.AssetItem;

public record TtsGenerateResponse(
        Long taskId,
        String status,
        AssetItem audioAsset
) {
}
