package com.huashuo.asset.controller;

import com.huashuo.asset.dto.AssetGroupUpdateRequest;
import com.huashuo.asset.dto.AssetContentUpdateRequest;
import com.huashuo.asset.dto.AssetCoverUpdateRequest;
import com.huashuo.asset.dto.CarModelBundleUpdateRequest;
import com.huashuo.asset.vo.AssetContent;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.asset.service.AssetService;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.upload.service.UploadService;
import com.huashuo.user.service.UserAuthService;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    private final UploadService uploadService;

    public AssetController(AssetService assetService, UserAuthService userAuthService, UploadService uploadService) {
        this.assetService = assetService;
        this.userAuthService = userAuthService;
        this.uploadService = uploadService;
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
            @RequestParam(required = false) String assetGroup,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Boolean includePreview
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(
                assetService.listProjectAssets(viewer, scope, projectId, assetType, keyword, sourceType, assetGroup, sort,
                        pageNo, pageSize, includePreview),
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

    @PostMapping("/upload")
    public ApiResponse<AssetItem> uploadMaterialAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "false") boolean publish,
            @RequestParam(required = false) String metadataJson,
            @RequestParam @NotNull MultipartFile file
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        if (viewer.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再上传到私有资产");
        }
        return ApiResponse.success(
                uploadService.uploadMaterialAsset(projectId, file, viewer.getAsLong(), publish, metadataJson),
                traceId());
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

    @PatchMapping("/{assetId}/group")
    public ApiResponse<AssetItem> updateAssetGroup(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId,
            @RequestBody(required = false) AssetGroupUpdateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        String assetGroup = request == null ? null : request.assetGroup();
        return ApiResponse.success(assetService.updateAssetGroup(assetId, assetGroup, viewer), traceId());
    }

    @PatchMapping("/{assetId}/cover")
    public ApiResponse<AssetItem> updateAssetCover(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId,
            @RequestBody(required = false) AssetCoverUpdateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(
                assetService.updateAssetCover(
                        assetId,
                        request == null ? null : request.thumbnailUrl(),
                        request == null ? null : request.metadataJson(),
                        viewer),
                traceId());
    }

    @PatchMapping("/{assetId}/car-model-bundle")
    public ApiResponse<AssetItem> updateCarModelBundle(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId,
            @RequestBody CarModelBundleUpdateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(
                assetService.updateCarModelBundle(
                        assetId,
                        request == null ? null : request.fileName(),
                        request == null ? null : request.contentJson(),
                        request == null ? null : request.metadataJson(),
                        viewer),
                traceId());
    }

    @PatchMapping("/{assetId}/content")
    public ApiResponse<AssetItem> updateEditableTextAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long assetId,
            @RequestBody AssetContentUpdateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(
                assetService.updateEditableTextAsset(
                        assetId,
                        request == null ? null : request.fileName(),
                        request == null ? null : request.content(),
                        request == null ? null : request.metadataJson(),
                        viewer),
                traceId());
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
