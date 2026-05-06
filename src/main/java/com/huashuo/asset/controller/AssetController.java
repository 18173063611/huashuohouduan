package com.huashuo.asset.controller;

import com.huashuo.asset.vo.AssetItem;
import com.huashuo.asset.service.AssetService;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
/**
 * 资产管理接口：按项目查询上传素材、脚本产物、TTS 音频等可复用资产。
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    public ApiResponse<List<AssetItem>> listProjectAssets(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String assetType
    ) {
        return ApiResponse.success(assetService.listProjectAssets(projectId, assetType), traceId());
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AssetItem> getAsset(@PathVariable Long assetId) {
        return ApiResponse.success(assetService.getAsset(assetId), traceId());
    }

    @PostMapping("/{assetId}/save")
    public ApiResponse<AssetItem> saveAsset(@PathVariable Long assetId) {
        return ApiResponse.success(assetService.getAsset(assetId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
