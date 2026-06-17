package com.huashuo.avatar.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AvatarGenerateRequest(
        Long projectId,
        @NotBlank @Size(max = 80) String avatarName,
        @NotBlank @Size(max = 2000) String prompt,
        List<Long> referenceAssetIds,
        String style,
        @Size(max = 40) String framing,
        @Size(max = 80) String outfitPreset,
        @Size(max = 500) String outfitDescription,
        @Min(120) @Max(230) Integer heightCm,
        @Min(30) @Max(220) Integer weightKg,
        @Min(1) @Max(4) Integer imageCount,
        String size
) {
}
