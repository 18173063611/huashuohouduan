package com.huashuo.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        Long projectId,

        @NotBlank(message = "Task type is required")
        @Size(max = 50, message = "Task type cannot exceed 50 characters")
        String taskType,

        String inputJson
) {
}
