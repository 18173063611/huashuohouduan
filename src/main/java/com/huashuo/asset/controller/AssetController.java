package com.huashuo.asset.controller;

import com.huashuo.asset.vo.AssetContent;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.asset.service.AssetService;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.service.UserAuthService;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

@Validated
/**
 * 资产管理接口：按项目查询上传素材、脚本产物、TTS 音频等可复用资产。
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;
    private final UserAuthService userAuthService;

    public AssetController(AssetService assetService, UserAuthService userAuthService) {
        this.assetService = assetService;
        this.userAuthService = userAuthService;
    }

    @GetMapping
    public ApiResponse<List<AssetItem>> listProjectAssets(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String sort
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(
                assetService.listProjectAssets(viewer, scope, projectId, assetType, keyword, sourceType, sort),
                traceId());
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AssetItem> getAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(assetService.getAssetForViewer(assetId, viewer), traceId());
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<String> getAssetContent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        AssetContent content = assetService.getGeneratedAssetContent(assetId, viewer);
        return ResponseEntity.ok()
                .contentType(parseContentType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(content.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(content.content());
    }

    @PostMapping("/{assetId}/save")
    public ApiResponse<AssetItem> saveAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(assetService.saveAssetToUserCollection(assetId, viewer), traceId());
    }

    @PostMapping("/{assetId}/publish")
    public ApiResponse<AssetItem> publishAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(assetService.publishAsset(assetId, viewer), traceId());
    }

    @PostMapping("/{assetId}/unpublish")
    public ApiResponse<AssetItem> unpublishAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(assetService.unpublishAsset(assetId, viewer), traceId());
    }

    @DeleteMapping("/{assetId}")
    public ApiResponse<Void> deleteAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        assetService.deleteAssetForViewer(assetId, viewer);
        return ApiResponse.success(null, traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private MediaType parseContentType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_JSON;
        }
    }
}
