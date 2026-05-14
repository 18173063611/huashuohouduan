package com.huashuo.writer.controller;


import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.writer.dto.VideoScriptSubmitRequest;
import com.huashuo.writer.service.WriterAsyncTaskService;
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
 * <p>每次请求都会通过 {@link WriterAsyncTaskService} 创建 task_type 为 {@code VIDEO_SCRIPT_ANALYZE} 或
 * {@code VIDEO_SCRIPT_URL_ANALYZE} 的本地任务，内部走 RabbitMQ + {@link com.huashuo.task.service.TaskService#createTask}，
 * 按 {@code ai_billing_step_config} 预扣积分。请求须携带 {@code Authorization} / {@code X-Auth-Token}（与全局登录态一致），
 * 本控制器从请求头显式解析 userId，与预扣链路对齐。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/video/script")
public class VideoScriptController {

    private final WriterAsyncTaskService writerAsyncTaskService;
    private final UserAuthService userAuthService;

    public VideoScriptController(WriterAsyncTaskService writerAsyncTaskService, UserAuthService userAuthService) {
        this.writerAsyncTaskService = writerAsyncTaskService;
        this.userAuthService = userAuthService;
    }

    @PostMapping("/analy")
    public ApiResponse<TaskItem> scriptAnalyze(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        long userId = userAuthService.requireUserId(authorization, xAuthToken);
        return ApiResponse.success(
<<<<<<< HEAD
                writerAsyncTaskService.createVideoScriptAnalyzeTask(url, traceId(), CurrentUser.nullableUserId(),
                        projectId, trimIdempotencyKey(idempotencyHeader)),
=======
                writerAsyncTaskService.createVideoScriptAnalyzeTask(new VideoScriptSubmitRequest(url), userId,
                        projectId, traceId(), trimIdempotency(idempotencyHeader)),
>>>>>>> fwx
                traceId()
        );
    }

    @PostMapping("/url")
    public ApiResponse<TaskItem> scriptUrl(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        long userId = userAuthService.requireUserId(authorization, xAuthToken);
        return ApiResponse.success(
<<<<<<< HEAD
                writerAsyncTaskService.createVideoScriptUrlAnalyzeTask(url, traceId(), CurrentUser.nullableUserId(),
                        projectId, trimIdempotencyKey(idempotencyHeader)),
=======
                writerAsyncTaskService.createVideoScriptUrlAnalyzeTask(new VideoScriptSubmitRequest(url), userId,
                        projectId, traceId(), trimIdempotency(idempotencyHeader)),
>>>>>>> fwx
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
