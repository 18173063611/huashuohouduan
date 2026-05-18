package com.huashuo.writer.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.mq.AiTaskPublisher;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.limit.WriterRequestRateLimiter;
import com.huashuo.writer.service.WriterAsyncTaskService;
import com.huashuo.writer.service.WriterService;
import com.huashuo.writer.sse.DouyinParseTranscriptSseService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/v1/writer")
@Slf4j
public class WriterController {

    private final WriterService writerService;
    private final WriterAsyncTaskService writerAsyncTaskService;
    private final AiTaskPublisher aiTaskPublisher;
    private final DouyinParseTranscriptSseService sseService;
    private final WriterRequestRateLimiter writerRequestRateLimiter;

    public WriterController(WriterService writerService, WriterAsyncTaskService writerAsyncTaskService,
                            AiTaskPublisher aiTaskPublisher, DouyinParseTranscriptSseService sseService,
                            WriterRequestRateLimiter writerRequestRateLimiter) {
        this.writerService = writerService;
        this.writerAsyncTaskService = writerAsyncTaskService;
        this.aiTaskPublisher = aiTaskPublisher;
        this.sseService = sseService;
        this.writerRequestRateLimiter = writerRequestRateLimiter;
    }


    @PostMapping("/douyin/parse")
    public ApiResponse<DouyinVideoParseResponse> parseDouyinVideo(@RequestBody DouyinVideoParseRequest request) {
        writerRequestRateLimiter.assertDouyinParseAllowed(CurrentUser.nullableUserId());
        return ApiResponse.success(writerService.parseDouyinVideo(request), traceId());
    }

    @PostMapping(value = "/douyin/parse-with-transcript", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter parseDouyinVideoWithTranscript(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody DouyinVideoParseRequest request
    ) {
        TaskItem task = writerAsyncTaskService.createDouyinParseTranscriptTask(
                request,
                traceId(),
                CurrentUser.nullableUserId(),
                trimIdempotencyKey(idempotencyHeader)
        );
        SseEmitter emitter = sseService.createAndRegister(task.taskId());
        aiTaskPublisher.publishAfterCommit(task);
        return emitter;
    }

    @PostMapping("/douyin/rewrite")
    public ApiResponse<TaskItem> rewriteDouyinVideo(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody RewriteDTO request
    ) {
        return ApiResponse.success(
                writerAsyncTaskService.createDouyinRewriteTask(request, traceId(), CurrentUser.nullableUserId(),
                        trimIdempotencyKey(idempotencyHeader)),
                traceId()
        );
    }

    @PostMapping("/douyin/transcript")
    public ApiResponse<TaskItem> extractDouyinVideoTranscript(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody DouyinVideoTranscriptRequest request
    ) {
        return ApiResponse.success(
                writerAsyncTaskService.createDouyinTranscriptTask(request, traceId(), CurrentUser.nullableUserId(),
                        trimIdempotencyKey(idempotencyHeader)),
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
