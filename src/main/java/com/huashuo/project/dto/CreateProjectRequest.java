package com.huashuo.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "Project name is required")
        @Size(max = 80, message = "Project name cannot exceed 80 characters")
        String projectName,

        @Size(max = 500, message = "Project description cannot exceed 500 characters")
        String description
) {
}
