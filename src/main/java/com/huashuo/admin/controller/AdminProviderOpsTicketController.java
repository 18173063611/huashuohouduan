package com.huashuo.admin.controller;

import com.huashuo.admin.dto.AdminTaskManualRetryApplyRequest;
import com.huashuo.admin.dto.AdminTaskManualRetryRequest;
import com.huashuo.admin.dto.AdminTaskProviderOpsUpdateRequest;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.service.AdminProviderOpsTicketService;
import com.huashuo.admin.vo.AdminProviderOpsTicketItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/provider-ops/tickets")
public class AdminProviderOpsTicketController {

    private final AdminProviderOpsTicketService ticketService;

    public AdminProviderOpsTicketController(AdminProviderOpsTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminProviderOpsTicketItem>> listTickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeAdminId,
            @RequestParam(required = false) Boolean overdueOnly,
            @RequestParam(required = false) String supplierTicketId,
            @RequestParam(required = false) String providerTaskId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String retryApprovalStatus,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(
                ticketService.listTickets(status, priority, assigneeAdminId, overdueOnly, supplierTicketId,
                        providerTaskId, taskType, retryApprovalStatus, pageNo, pageSize),
                traceId()
        );
    }

    @PatchMapping("/{ticketId}")
    public ApiResponse<AdminProviderOpsTicketItem> updateTicket(
            @PathVariable Long ticketId,
            @Valid @RequestBody AdminTaskProviderOpsUpdateRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(
                ticketService.updateTicket(ticketId, request, operationContext(servletRequest)),
                traceId()
        );
    }

    @PostMapping("/{ticketId}/manual-retry")
    public ApiResponse<AdminProviderOpsTicketItem> requestManualRetry(
            @PathVariable Long ticketId,
            @Valid @RequestBody AdminTaskManualRetryApplyRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(
                ticketService.requestManualRetry(ticketId, request, operationContext(servletRequest)),
                traceId()
        );
    }

    @PostMapping("/{ticketId}/manual-retry/review")
    public ApiResponse<AdminProviderOpsTicketItem> reviewManualRetry(
            @PathVariable Long ticketId,
            @Valid @RequestBody AdminTaskManualRetryRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(
                ticketService.reviewManualRetry(ticketId, request, operationContext(servletRequest)),
                traceId()
        );
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
