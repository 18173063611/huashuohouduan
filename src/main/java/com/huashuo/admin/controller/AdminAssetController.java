package com.huashuo.admin.controller;

import com.huashuo.admin.service.AdminAssetService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/assets")
public class AdminAssetController {

    private final AdminAssetService adminAssetService;

    public AdminAssetController(AdminAssetService adminAssetService) {
        this.adminAssetService = adminAssetService;
    }

    @GetMapping
    public ApiResponse<PageResult<AssetItem>> listAssets(
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String assetGroup,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(
                adminAssetService.listAssets(ownerUserId, visibility, status, assetType, sourceType, assetGroup, keyword,
                        pageNo, pageSize),
                traceId()
        );
    }

    @PostMapping("/{assetId}/public")
    public ApiResponse<AssetItem> setPublic(@PathVariable Long assetId, HttpServletRequest request) {
        return ApiResponse.success(
                adminAssetService.setVisibility(assetId, "PUBLIC", operationContext(request)),
                traceId()
        );
    }

    @PostMapping("/{assetId}/private")
    public ApiResponse<AssetItem> setPrivate(@PathVariable Long assetId, HttpServletRequest request) {
        return ApiResponse.success(
                adminAssetService.setVisibility(assetId, "PRIVATE", operationContext(request)),
                traceId()
        );
    }

    @PostMapping("/{assetId}/remove")
    public ApiResponse<AssetItem> remove(@PathVariable Long assetId, HttpServletRequest request) {
        return ApiResponse.success(
                adminAssetService.setStatus(assetId, "REMOVED", operationContext(request)),
                traceId()
        );
    }

    @PostMapping("/{assetId}/restore")
    public ApiResponse<AssetItem> restore(@PathVariable Long assetId, HttpServletRequest request) {
        return ApiResponse.success(
                adminAssetService.setStatus(assetId, "ACTIVE", operationContext(request)),
                traceId()
        );
    }

    @DeleteMapping("/{assetId}")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId, HttpServletRequest request) {
        adminAssetService.deleteAsset(assetId, operationContext(request));
        return ApiResponse.success(null, traceId());
    }

    private AdminOperationContext operationContext(HttpServletRequest request) {
        Object currentUserId = request.getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        Long adminUserId = currentUserId instanceof Long userId ? userId : null;
        return new AdminOperationContext(adminUserId, clientIp(request), traceId());
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
