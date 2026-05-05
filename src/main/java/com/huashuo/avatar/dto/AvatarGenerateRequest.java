package com.huashuo.avatar.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AvatarGenerateRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 80) String avatarName,
        @NotBlank @Size(max = 2000) String prompt,
        List<Long> referenceAssetIds,
        String style,
        @Min(1) @Max(4) Integer imageCount,
        String size
) {
}
