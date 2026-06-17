package com.huashuo.support.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.support.dto.CustomerFeedbackCreateRequest;
import com.huashuo.support.service.CustomerFeedbackService;
import com.huashuo.support.vo.CustomerFeedbackItem;
import com.huashuo.user.util.CurrentUser;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/feedback")
public class CustomerFeedbackController {

    private final CustomerFeedbackService customerFeedbackService;

    public CustomerFeedbackController(CustomerFeedbackService customerFeedbackService) {
        this.customerFeedbackService = customerFeedbackService;
    }

    @PostMapping
    public ApiResponse<CustomerFeedbackItem> create(@Valid @RequestBody CustomerFeedbackCreateRequest request) {
        return ApiResponse.success(customerFeedbackService.create(currentUserId(), request), traceId());
    }

    @GetMapping
    public ApiResponse<PageResult<CustomerFeedbackItem>> listMine(
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(customerFeedbackService.listMine(currentUserId(), pageNo, pageSize), traceId());
    }

    private long currentUserId() {
        return CurrentUser.optionalUserId()
                .orElseThrow(() -> new BusinessException(40100, "未登录或登录已过期"));
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
