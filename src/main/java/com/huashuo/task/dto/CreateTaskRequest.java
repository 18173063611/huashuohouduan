package com.huashuo.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        Long projectId,

        @NotBlank(message = "Task type is required")
        @Size(max = 50, message = "Task type cannot exceed 50 characters")
        String taskType,

        String inputJson,

        /** 与请求头 Idempotency-Key 二选一或同时传，请求头优先；同一键重复提交返回已有任务且不重复扣费 */
        @Size(max = 120, message = "Idempotency key cannot exceed 120 characters")
        String idempotencyKey
) {
    public CreateTaskRequest {
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
    }
}
