package com.huashuo.admin.controller;

import com.huashuo.admin.dto.AdminCreditAdjustRequest;
import com.huashuo.admin.dto.AdminPasswordResetRequest;
import com.huashuo.admin.dto.AdminUserCreateRequest;
import com.huashuo.admin.dto.AdminUserUpdateRequest;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.service.AdminUserService;
import com.huashuo.admin.vo.AdminCreditAccountResponse;
import com.huashuo.admin.vo.AdminCreditLogItem;
import com.huashuo.admin.vo.AdminUserItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminUserItem>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(adminUserService.listUsers(keyword, role, status, pageNo, pageSize), traceId());
    }

    @GetMapping("/{userId}")
    public ApiResponse<AdminUserItem> getUser(@PathVariable Long userId) {
        return ApiResponse.success(adminUserService.getUser(userId), traceId());
    }

    @PostMapping
    public ApiResponse<AdminUserItem> createUser(@Valid @RequestBody AdminUserCreateRequest request,
                                                 HttpServletRequest servletRequest) {
        return ApiResponse.success(adminUserService.createUser(request, operationContext(servletRequest)), traceId());
    }

    @PutMapping("/{userId}")
    public ApiResponse<AdminUserItem> updateUser(@PathVariable Long userId,
                                                 @Valid @RequestBody AdminUserUpdateRequest request,
                                                 HttpServletRequest servletRequest) {
        return ApiResponse.success(adminUserService.updateUser(userId, request, operationContext(servletRequest)),
                traceId());
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId, HttpServletRequest request) {
        adminUserService.deleteUser(userId, operationContext(request));
        return ApiResponse.success(null, traceId());
    }

    @PostMapping("/{userId}/enable")
    public ApiResponse<AdminUserItem> enableUser(@PathVariable Long userId, HttpServletRequest servletRequest) {
        return ApiResponse.success(adminUserService.enableUser(userId, operationContext(servletRequest)), traceId());
    }

    @PostMapping("/{userId}/disable")
    public ApiResponse<AdminUserItem> disableUser(@PathVariable Long userId, HttpServletRequest servletRequest) {
        return ApiResponse.success(adminUserService.disableUser(userId, operationContext(servletRequest)), traceId());
    }

    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long userId,
                                           @Valid @RequestBody AdminPasswordResetRequest request,
                                           HttpServletRequest servletRequest) {
        adminUserService.resetPassword(userId, request, operationContext(servletRequest));
        return ApiResponse.success(null, traceId());
    }

    @GetMapping("/{userId}/credits")
    public ApiResponse<AdminCreditAccountResponse> getCreditAccount(@PathVariable Long userId) {
        return ApiResponse.success(adminUserService.getCreditAccount(userId), traceId());
    }

    @PostMapping("/{userId}/credits/adjust")
    public ApiResponse<AdminCreditAccountResponse> adjustCredits(@PathVariable Long userId,
                                                                 @Valid @RequestBody AdminCreditAdjustRequest request,
                                                                 HttpServletRequest servletRequest) {
        return ApiResponse.success(adminUserService.adjustCredits(userId, request, operationContext(servletRequest)),
                traceId());
    }

    @GetMapping("/{userId}/credit-logs")
    public ApiResponse<PageResult<AdminCreditLogItem>> listCreditLogs(
            @PathVariable Long userId,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.success(adminUserService.listCreditLogs(userId, pageNo, pageSize), traceId());
    }

    private Long currentUserId(HttpServletRequest request) {
        Object currentUserId = request.getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        return currentUserId instanceof Long userId ? userId : null;
    }

    private AdminOperationContext operationContext(HttpServletRequest request) {
        return new AdminOperationContext(currentUserId(request), clientIp(request), traceId());
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
