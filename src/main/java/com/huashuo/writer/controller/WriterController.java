package com.huashuo.writer.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.job.WriterTaskExecutor;
import com.huashuo.writer.limit.WriterRequestRateLimiter;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoParseWithTranscriptEvent;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.VideoDownloadResource;
import com.huashuo.writer.service.WriterAsyncTaskService;
import com.huashuo.writer.service.WriterService;
import com.huashuo.writer.sse.DouyinParseTranscriptSseService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Validated
@RestController
@RequestMapping("/api/v1/writer")
@Slf4j
public class WriterController {

    private final WriterService writerService;
    private final WriterAsyncTaskService writerAsyncTaskService;
    private final DouyinParseTranscriptSseService sseService;
    private final WriterRequestRateLimiter writerRequestRateLimiter;
    private final WriterTaskExecutor writerTaskExecutor;

    public WriterController(WriterService writerService, WriterAsyncTaskService writerAsyncTaskService,
                            DouyinParseTranscriptSseService sseService,
                            WriterRequestRateLimiter writerRequestRateLimiter,
                            WriterTaskExecutor writerTaskExecutor) {
        this.writerService = writerService;
        this.writerAsyncTaskService = writerAsyncTaskService;
        this.sseService = sseService;
        this.writerRequestRateLimiter = writerRequestRateLimiter;
        this.writerTaskExecutor = writerTaskExecutor;
    }


    @PostMapping("/douyin/parse")
    public ApiResponse<DouyinVideoParseResponse> parseDouyinVideo(@RequestBody DouyinVideoParseRequest request) {
        writerRequestRateLimiter.assertDouyinParseAllowed(CurrentUser.nullableUserId());
        return ApiResponse.success(writerService.parseDouyinVideo(request), traceId());
    }

    @PostMapping(value = "/videos/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> downloadShareVideo(@RequestBody DouyinVideoParseRequest request) {
        writerRequestRateLimiter.assertDouyinParseAllowed(CurrentUser.nullableUserId());
        VideoDownloadResource resource = writerService.openShareVideoDownload(request);
        StreamingResponseBody body = outputStream -> {
            try (resource) {
                resource.writeTo(outputStream);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(resource.fileName(), StandardCharsets.UTF_8)
                .build());
        if (resource.contentLength() > 0) {
            headers.setContentLength(resource.contentLength());
        }
        MediaType mediaType = resolveMediaType(resource.contentType());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(mediaType)
                .body(body);
    }

    @GetMapping("/media/cover")
    public ResponseEntity<StreamingResponseBody> proxyCoverImage(@RequestParam("url") String imageUrl) {
        VideoDownloadResource resource = writerService.openRemoteCoverImage(imageUrl);
        StreamingResponseBody body = outputStream -> {
            try (resource) {
                resource.writeTo(outputStream);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        if (resource.contentLength() > 0) {
            headers.setContentLength(resource.contentLength());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .contentType(resolveMediaType(resource.contentType()))
                .body(body);
    }

    @PostMapping(value = "/douyin/parse-with-transcript", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter parseDouyinVideoWithTranscript(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody DouyinVideoParseRequest request
    ) {
        return createParseWithTranscriptEmitter(idempotencyHeader, request, null);
    }

    @PostMapping(value = "/{platform}/parse-with-transcript", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter parsePlatformVideoWithTranscript(
            @PathVariable("platform") String platform,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody DouyinVideoParseRequest request
    ) {
        return createParseWithTranscriptEmitter(idempotencyHeader, request, platform);
    }

    private SseEmitter createParseWithTranscriptEmitter(String idempotencyHeader,
                                                       DouyinVideoParseRequest request,
                                                       String platform) {
        if (request == null) {
            request = new DouyinVideoParseRequest();
        }
        if (StringUtils.hasText(platform)) {
            request.setPlatform(platform);
            if (isUploadParsePlatform(platform)) {
                request.setSourceType("upload");
            }
        }
        TaskItem task = writerAsyncTaskService.createDouyinParseTranscriptTask(
                request,
                traceId(),
                CurrentUser.nullableUserId(),
                trimIdempotencyKey(idempotencyHeader)
        );
        SseEmitter emitter = sseService.createAndRegister(task.taskId());
        try {
            sseService.send(
                    task.taskId(),
                    "accepted",
                    new DouyinVideoParseWithTranscriptEvent("accepted", task.taskId(), null, null),
                    "解析任务已创建，正在排队处理"
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to send parse accepted SSE. taskId={}, reason={}", task.taskId(), exception.getMessage());
        }
        runParseTaskLocally(task);
        return emitter;
    }

    private void runParseTaskLocally(TaskItem task) {
        if (task == null || task.taskId() == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                writerTaskExecutor.run(task.taskId());
            } catch (RuntimeException exception) {
                log.error("Local parse-with-transcript task {} failed unexpectedly", task.taskId(), exception);
            }
        });
    }

    private boolean isUploadParsePlatform(String platform) {
        String normalized = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        return "upload".equals(normalized)
                || "local".equals(normalized)
                || "localupload".equals(normalized)
                || "direct".equals(normalized);
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

    private static MediaType resolveMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
