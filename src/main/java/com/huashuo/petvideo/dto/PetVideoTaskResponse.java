package com.huashuo.petvideo.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record PetVideoTaskResponse(
        String id,
        String title,
        String status,
        Integer progress,
        String currentStep,
        Integer estimatedRemainSeconds,
        JsonNode draft,
        String previewUrl,
        String workId,
        String errorCode,
        Boolean retryable,
        String errorMessage,
        String createdAt
) {
}
