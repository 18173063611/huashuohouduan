package com.huashuo.admin.controller;

import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.support.dto.CustomerFeedbackAdminUpdateRequest;
import com.huashuo.support.service.CustomerFeedbackService;
import com.huashuo.support.vo.CustomerFeedbackItem;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminCustomerFeedbackController {

    private final CustomerFeedbackService customerFeedbackService;

    public AdminCustomerFeedbackController(CustomerFeedbackService customerFeedbackService) {
        this.customerFeedbackService = customerFeedbackService;
    }

    @GetMapping
    public ApiResponse<PageResult<CustomerFeedbackItem>> list(
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(
                customerFeedbackService.listAdmin(ownerUserId, category, status, priority, keyword, pageNo, pageSize),
                traceId()
        );
    }

    @GetMapping("/{feedbackId}")
    public ApiResponse<CustomerFeedbackItem> get(@PathVariable Long feedbackId) {
        return ApiResponse.success(customerFeedbackService.getAdmin(feedbackId), traceId());
    }

    @PutMapping("/{feedbackId}")
    public ApiResponse<CustomerFeedbackItem> update(@PathVariable Long feedbackId,
                                                    @Valid @RequestBody CustomerFeedbackAdminUpdateRequest request,
                                                    HttpServletRequest servletRequest) {
        return ApiResponse.success(
                customerFeedbackService.updateAdmin(feedbackId, request, operationContext(servletRequest)),
                traceId()
        );
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
