package com.huashuo.task.vo;

public record TaskResultResponse(
        Long taskId,
        Long projectId,
        Long ownerUserId,
        String taskType,
        String taskTitle,
        String status,
        Integer progress,
        String errorCode,
        String errorMessage,
        Object result
) {
}
