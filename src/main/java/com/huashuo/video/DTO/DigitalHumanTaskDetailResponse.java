package com.huashuo.video.dto;

public record DigitalHumanTaskDetailResponse(
        Long taskId,
        Long projectId,
        String taskType,
        String status,
        Integer progress,
        String errorMessage,
        String model,
        String videoUrl,
        Long resultAssetId,
        String coverUrl,
        Integer credits
) {
}
