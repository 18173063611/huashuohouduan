package com.huashuo.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VideoParseRequest(
        @NotNull Long projectId,
        @NotBlank String videoUrl
) {
}
