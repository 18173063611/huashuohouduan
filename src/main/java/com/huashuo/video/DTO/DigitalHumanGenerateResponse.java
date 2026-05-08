package com.huashuo.video.dto;

public record DigitalHumanGenerateResponse(
        Long taskId,
        Long projectId,
        String taskType,
        String status
) {
}
