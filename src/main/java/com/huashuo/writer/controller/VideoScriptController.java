package com.huashuo.writer.controller;


import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.writer.service.WriterAsyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频理解接口：调用方舟 doubao-seed-2-0-lite 多模态模型对单条视频做分镜解析。
 *
 * <p>每次请求都会通过 {@link WriterAsyncTaskService} 创建一条 task_type={@code VIDEO_PARSE} 的本地任务，
 * 内部走 RabbitMQ + {@link com.huashuo.task.service.TaskService#createTask}，按 {@code ai_billing_step_config}
 * 配置预扣积分；老接口契约保持不变（仅在请求头/参数新增可选 {@code Idempotency-Key} 与 {@code projectId}），
 * 鉴权信息由 {@code LoginAuthInterceptor} 通过 {@link CurrentUser} 提供，匿名调用不扣费。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/video/script")
@Slf4j
public class VideoScriptController {

    private final WriterAsyncTaskService writerAsyncTaskService;

    public VideoScriptController(WriterAsyncTaskService writerAsyncTaskService) {
        this.writerAsyncTaskService = writerAsyncTaskService;
    }

    @PostMapping("/analy")
    public ApiResponse<TaskItem> scriptAnalyze(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        return ApiResponse.success(
                writerAsyncTaskService.createVideoScriptAnalyzeTask(url, traceId(), CurrentUser.nullableUserId(),
                        projectId, trimIdempotencyKey(idempotencyHeader)),
                traceId()
        );
    }

    @PostMapping("/url")
    public ApiResponse<TaskItem> scriptUrl(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        return ApiResponse.success(
                writerAsyncTaskService.createVideoScriptUrlAnalyzeTask(url, traceId(), CurrentUser.nullableUserId(),
                        projectId, trimIdempotencyKey(idempotencyHeader)),
                traceId()
        );
    }


    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private static String trimIdempotencyKey(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
