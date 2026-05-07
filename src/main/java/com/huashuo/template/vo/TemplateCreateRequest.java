package com.huashuo.template.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TemplateCreateRequest(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 1000) String description,
        Long coverAssetId,
        List<TemplateAssetBind> assets,
        String tags,
        String metadataJson
) {
    public record TemplateAssetBind(Long assetId, String role) {
    }
}

