package com.huashuo.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TtsGenerateRequest(
        @NotNull Long projectId,
        @NotNull Long scriptVersionId,
        @NotBlank String voiceCode
) {
}
