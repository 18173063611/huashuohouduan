package com.huashuo.avatar.dto;

public record AvatarGenerateResponse(
        Long taskId,
        Long projectId,
        String taskType,
        String status
) {
}
