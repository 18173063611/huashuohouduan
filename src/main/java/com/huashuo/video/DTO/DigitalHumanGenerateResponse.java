package com.huashuo.video.DTO;

public record DigitalHumanGenerateResponse(
        Long taskId,
        Long projectId,
        String taskType,
        String status
) {
}
