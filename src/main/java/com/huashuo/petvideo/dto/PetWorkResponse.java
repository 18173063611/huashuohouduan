package com.huashuo.petvideo.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record PetWorkResponse(
        String id,
        String title,
        String templateTitle,
        String petType,
        String status,
        String aspectRatio,
        Integer durationSeconds,
        String coverUrl,
        String videoUrl,
        String downloadUrl,
        JsonNode draft,
        String errorCode,
        String errorMessage,
        Boolean retryable,
        String createdAt
) {
}
