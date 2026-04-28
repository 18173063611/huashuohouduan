package com.huashuo.project.vo;

import java.time.LocalDateTime;

public record FwxProjectItem(
        Long projectId,
        String projectName,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
