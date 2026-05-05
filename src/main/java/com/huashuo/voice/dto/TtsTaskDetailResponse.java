package com.huashuo.voice.dto;

import com.huashuo.asset.vo.AssetItem;

public record TtsTaskDetailResponse(
        Long taskId,
        Long projectId,
        String taskType,
        String status,
        Integer progress,
        String errorMessage,
        AssetItem audioAsset
) {
}
