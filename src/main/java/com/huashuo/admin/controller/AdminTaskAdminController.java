package com.huashuo.admin.controller;

import com.huashuo.admin.service.AdminTaskAdminService;
import com.huashuo.admin.vo.AdminTaskItem;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tasks")
public class AdminTaskAdminController {

    private final AdminTaskAdminService adminTaskAdminService;

    public AdminTaskAdminController(AdminTaskAdminService adminTaskAdminService) {
        this.adminTaskAdminService = adminTaskAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminTaskItem>> listTasks(
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(
                adminTaskAdminService.listTasks(ownerUserId, taskType, status, modelCode, pageNo, pageSize),
                traceId()
        );
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
