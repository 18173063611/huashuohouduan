package com.huashuo.admin.controller;

import com.huashuo.admin.service.AdminOperationLogService;
import com.huashuo.admin.vo.AdminOperationLogItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operation-logs")
public class AdminOperationLogController {

    private final AdminOperationLogService adminOperationLogService;

    public AdminOperationLogController(AdminOperationLogService adminOperationLogService) {
        this.adminOperationLogService = adminOperationLogService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminOperationLogItem>> listLogs(
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.success(
                adminOperationLogService.listLogs(adminUserId, operationType, targetType, pageNo, pageSize),
                traceId()
        );
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
