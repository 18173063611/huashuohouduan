package com.huashuo.task.ws;

public record TaskStatusMessage(
        Long taskId,
        Long ownerUserId,
        String taskType,
        String status,
        Integer progress,
        String errorMessage
) {
}
