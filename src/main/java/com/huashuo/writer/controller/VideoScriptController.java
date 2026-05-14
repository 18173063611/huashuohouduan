package com.huashuo.writer.controller;


import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.writer.service.WriterAsyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam String url
    ) {
        return ApiResponse.success(
                writerAsyncTaskService.createVideoScriptAnalyzeTask(url, traceId(), CurrentUser.nullableUserId(),
                        trimIdempotencyKey(idempotencyHeader)),
                traceId()
        );
    }

    @PostMapping("/url")
    public ApiResponse<TaskItem> scriptUrl(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url
    ) {
        return ApiResponse.success(
                writerAsyncTaskService.createVideoScriptUrlAnalyzeTask(url, traceId(), CurrentUser.nullableUserId(),
                        trimIdempotencyKey(idempotencyHeader)),
                traceId()
        );
    }


    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private static String trimIdempotencyKey(String value) {
        return org.springframework.util.StringUtils.hasText(value) ? value.trim() : null;
    }
}
