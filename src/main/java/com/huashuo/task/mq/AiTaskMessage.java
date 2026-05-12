package com.huashuo.task.mq;

public record AiTaskMessage(
        Long taskId,
        String taskType,
        Long ownerUserId,
        String traceId
) {
}
