package com.huashuo.task.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.dto.CreateTaskRequest;
import com.huashuo.task.job.TaskRetryDispatcher;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.task.vo.TaskSummaryResponse;
import com.huashuo.user.service.UserAuthService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalLong;

@Validated
/**
 * 任务管理接口：任务创建、列表筛选、汇总、详情、重试与取消等，供任务中心与各业务模块查看进度。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskRetryDispatcher taskRetryDispatcher;
    private final UserAuthService userAuthService;

    public TaskController(TaskService taskService, TaskRetryDispatcher taskRetryDispatcher,
                          UserAuthService userAuthService) {
        this.taskService = taskService;
        this.taskRetryDispatcher = taskRetryDispatcher;
        this.userAuthService = userAuthService;
    }

    @PostMapping
    public ApiResponse<TaskItem> createTask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(
                taskService.createTask(request.projectId(), request.taskType(), request.inputJson(), traceId(),
                        ownerUserId),
                traceId()
        );
    }

    @GetMapping
    public ApiResponse<List<TaskItem>> listTasks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(
                taskService.listTasks(viewer, projectId, taskType, status, pageNo, pageSize),
                traceId()
        );
    }

    @GetMapping("/summary")
    public ApiResponse<TaskSummaryResponse> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) Long projectId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(taskService.getTaskSummary(viewer, projectId), traceId());
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskItem> getTask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(taskService.getTaskForViewer(taskId, viewer), traceId());
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskItem> retry(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        TaskItem task = taskService.retryTask(taskId, viewer);
        taskRetryDispatcher.dispatch(task);
        return ApiResponse.success(task, traceId());
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskItem> cancel(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(taskService.cancelTask(taskId, viewer), traceId());
    }

    @PatchMapping("/{taskId}/viewed")
    public ApiResponse<TaskItem> markViewed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(taskService.markTaskViewed(taskId, viewer), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
