package com.huashuo.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank(message = "Project name is required")
        @Size(max = 80, message = "Project name cannot exceed 80 characters")
        String projectName,

        @Size(max = 500, message = "Project description cannot exceed 500 characters")
        String description,

        @Size(max = 30, message = "Project status cannot exceed 30 characters")
        String status
) {
}
