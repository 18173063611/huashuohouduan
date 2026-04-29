package com.huashuo.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotNull(message = "Project id is required")
        Long projectId,

        @NotBlank(message = "Task type is required")
        @Size(max = 50, message = "Task type cannot exceed 50 characters")
        String taskType,

        String inputJson
) {
}
