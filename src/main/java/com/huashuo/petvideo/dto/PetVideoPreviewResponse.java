package com.huashuo.petvideo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record PetVideoPreviewResponse(
        boolean dryRun,
        boolean providerSubmitEnabled,
        boolean dryRunEnabled,
        boolean providerSubmitted,
        boolean taskCreated,
        boolean wouldCreateTask,
        String errorCode,
        String message,
        String taskType,
        String generationMode,
        Long estimatedCreditCost,
        Long balance,
        Boolean enoughBalance,
        String pricingSource,
        String modelCode,
        Integer durationSeconds,
        String aspectRatio,
        String style,
        String language,
        String promptPreview,
        String negativePrompt,
        JsonNode payloadPreview,
        JsonNode materialSummary,
        JsonNode storyboardShots,
        List<String> warnings
) {
}
