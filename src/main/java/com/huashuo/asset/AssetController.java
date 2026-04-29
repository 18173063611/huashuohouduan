package com.huashuo.asset;

import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    public ApiResponse<List<AssetItem>> listProjectAssets(
            @RequestParam @NotNull Long projectId,
            @RequestParam(required = false) String assetType
    ) {
        return ApiResponse.success(assetService.listProjectAssets(projectId, assetType), traceId());
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AssetItem> getAsset(@PathVariable Long assetId) {
        return ApiResponse.success(assetService.getAsset(assetId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
