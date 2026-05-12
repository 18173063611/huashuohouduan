package com.huashuo.admin.controller;

import com.huashuo.admin.service.AdminCreditLogService;
import com.huashuo.admin.vo.AdminCreditLogItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/credit-logs")
public class AdminCreditLogController {

    private final AdminCreditLogService adminCreditLogService;

    public AdminCreditLogController(AdminCreditLogService adminCreditLogService) {
        this.adminCreditLogService = adminCreditLogService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminCreditLogItem>> listLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) Long relatedTaskId,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.success(
                adminCreditLogService.listLogs(userId, changeType, relatedTaskId, pageNo, pageSize),
                traceId()
        );
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
