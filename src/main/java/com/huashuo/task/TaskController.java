package com.huashuo.task;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.dto.CreateTaskRequest;
import com.huashuo.task.vo.TaskItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<TaskItem> createTask(@Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.success(taskService.createTask(request, traceId()), traceId());
    }

    @GetMapping
    public ApiResponse<List<TaskItem>> listProjectTasks(@RequestParam @NotNull Long projectId) {
        return ApiResponse.success(taskService.listProjectTasks(projectId), traceId());
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskItem> getTask(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.getTask(taskId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
