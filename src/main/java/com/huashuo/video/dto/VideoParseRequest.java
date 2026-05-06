package com.huashuo.video.dto;

import jakarta.validation.constraints.NotBlank;

public record VideoParseRequest(
        Long projectId,
        @NotBlank String videoUrl
) {
}
