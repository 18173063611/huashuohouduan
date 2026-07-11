package com.huashuo.petasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PetImageGenerateRequest(
        @Size(max = 80) String name,
        @NotBlank @Size(max = 1200) String prompt,
        String kind,
        String style,
        @Min(1) @Max(4) Integer imageCount,
        String size,
        List<Long> referenceAssetIds
) {
}
