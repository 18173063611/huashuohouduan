package com.huashuo.task.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.dto.CreateTaskRequest;
import com.huashuo.task.job.TaskRetryDispatcher;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.task.vo.TaskResultResponse;
import com.huashuo.task.vo.TaskSummaryResponse;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

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

    public TaskController(TaskService taskService, TaskRetryDispatcher taskRetryDispatcher) {
        this.taskService = taskService;
        this.taskRetryDispatcher = taskRetryDispatcher;
    }

    @PostMapping
    @AiTaskSubmit
    public ApiResponse<TaskItem> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        OptionalLong viewer = currentUser();
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(
                taskService.createTask(request.projectId(), request.taskType(), request.inputJson(), traceId(),
                        ownerUserId, null, null, resolveIdempotencyKey(idempotencyHeader, request.idempotencyKey())),
                traceId()
        );
    }

    @GetMapping
    public ApiResponse<List<TaskItem>> listTasks(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        OptionalLong viewer = currentUser();
        return ApiResponse.success(
                taskService.listTasks(viewer, projectId, taskType, status, pageNo, pageSize),
                traceId()
        );
    }

    @GetMapping("/summary")
    public ApiResponse<TaskSummaryResponse> summary(
            @RequestParam(required = false) Long projectId
    ) {
        OptionalLong viewer = currentUser();
        return ApiResponse.success(taskService.getTaskSummary(viewer, projectId), traceId());
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskItem> getTask(
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = currentUser();
        return ApiResponse.success(taskService.getTaskForViewer(taskId, viewer), traceId());
    }

    @GetMapping("/{taskId}/result")
    public ApiResponse<TaskResultResponse> getTaskResult(
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = currentUser();
        return ApiResponse.success(taskService.getTaskResultForViewer(taskId, viewer), traceId());
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskItem> retry(
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = currentUser();
        TaskItem task = taskService.retryTask(taskId, viewer);
        taskRetryDispatcher.dispatch(task);
        return ApiResponse.success(task, traceId());
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskItem> cancel(
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = currentUser();
        return ApiResponse.success(taskService.cancelTask(taskId, viewer), traceId());
    }

    @PatchMapping("/{taskId}/viewed")
    public ApiResponse<TaskItem> markViewed(
            @PathVariable Long taskId
    ) {
        OptionalLong viewer = currentUser();
        return ApiResponse.success(taskService.markTaskViewed(taskId, viewer), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private OptionalLong currentUser() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return OptionalLong.empty();
        }
        Object userId = attributes.getRequest().getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        return userId instanceof Number number ? OptionalLong.of(number.longValue()) : OptionalLong.empty();
    }

    private String resolveIdempotencyKey(String header, String body) {
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        return StringUtils.hasText(body) ? body.trim() : null;
    }
}
