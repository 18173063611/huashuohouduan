package com.huashuo.admin.controller;

import com.huashuo.admin.dto.AdminModelSaveRequest;
import com.huashuo.admin.service.AdminModelService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.vo.AdminModelItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/models")
public class AdminModelController {

    private final AdminModelService adminModelService;

    public AdminModelController(AdminModelService adminModelService) {
        this.adminModelService = adminModelService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminModelItem>> listModels(
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(
                adminModelService.listModels(modelType, provider, enabled, pageNo, pageSize),
                traceId()
        );
    }

    @PostMapping
    public ApiResponse<AdminModelItem> saveModel(@Valid @RequestBody AdminModelSaveRequest request,
                                                 HttpServletRequest servletRequest) {
        return ApiResponse.success(adminModelService.saveModel(request, operationContext(servletRequest)), traceId());
    }

    @PutMapping("/{modelId}")
    public ApiResponse<AdminModelItem> updateModel(@PathVariable Long modelId,
                                                   @Valid @RequestBody AdminModelSaveRequest request,
                                                   HttpServletRequest servletRequest) {
        return ApiResponse.success(adminModelService.updateModel(modelId, request, operationContext(servletRequest)),
                traceId());
    }

    @PostMapping("/{modelId}/enable")
    public ApiResponse<AdminModelItem> enableModel(@PathVariable Long modelId, HttpServletRequest servletRequest) {
        return ApiResponse.success(adminModelService.setEnabled(modelId, true, operationContext(servletRequest)),
                traceId());
    }

    @PostMapping("/{modelId}/disable")
    public ApiResponse<AdminModelItem> disableModel(@PathVariable Long modelId, HttpServletRequest servletRequest) {
        return ApiResponse.success(adminModelService.setEnabled(modelId, false, operationContext(servletRequest)),
                traceId());
    }

    @PostMapping("/{modelId}/default")
    public ApiResponse<AdminModelItem> setDefault(@PathVariable Long modelId, HttpServletRequest servletRequest) {
        return ApiResponse.success(adminModelService.setDefault(modelId, operationContext(servletRequest)), traceId());
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
