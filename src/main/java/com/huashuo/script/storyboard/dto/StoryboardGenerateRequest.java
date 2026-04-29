package com.huashuo.script.storyboard.dto;

import jakarta.validation.constraints.NotNull;

public record StoryboardGenerateRequest(
        @NotNull Long projectId,
        @NotNull Long scriptVersionId
) {
}
