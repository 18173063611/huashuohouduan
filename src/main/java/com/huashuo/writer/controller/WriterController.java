package com.huashuo.writer.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseWithTranscriptEvent;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.WriterVO;
import com.huashuo.writer.service.WriterService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

@Validated
@RestController
@RequestMapping("/api/v1/writer")
@Slf4j
public class WriterController {

    private final WriterService writerService;
    private final Executor writerTaskExecutor;

    public WriterController(
            WriterService writerService,
            @Qualifier("writerTaskExecutor") Executor writerTaskExecutor
    ) {
        this.writerService = writerService;
        this.writerTaskExecutor = writerTaskExecutor;
    }

    /**
     * 前端传入抖音分享链接后，解析视频播放地址、文案标题、作者、封面等信息。
     */
    @PostMapping("/douyin/parse")
    public ApiResponse<DouyinVideoParseResponse> parseDouyinVideo(@RequestBody DouyinVideoParseRequest request) {
        DouyinVideoParseResponse douyinVideoParseResponse = writerService.parseDouyinVideo(request);
        return ApiResponse.success(douyinVideoParseResponse, traceId());
    }

    @PostMapping(value = "/douyin/parse-with-transcript", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter parseDouyinVideoWithTranscript(
            @RequestBody DouyinVideoParseRequest request
    ) {
        String traceId = traceId();
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());

        writerTaskExecutor.execute(() -> streamParseWithTranscript(emitter, request, traceId));
        log.info(String.valueOf(emitter));
        return emitter;
    }

    @PostMapping("/douyin/transcript")
    public ApiResponse<WriterVO> extractDouyinVideoTranscript(@RequestBody DouyinVideoTranscriptRequest request) {
        return ApiResponse.success(writerService.extractDouyinVideoTranscript(request), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private void streamParseWithTranscript(SseEmitter emitter, DouyinVideoParseRequest request, String traceId) {
        DouyinVideoParseResponse parseResult = null;
        try {
            parseResult = writerService.parseDouyinVideo(request);
            log.info("parseResult: {}", parseResult);
            log.info("解析dy得到视频链接，当前的时间：" + LocalDateTime.now());
            sendEvent(
                    emitter,
                    "parsed",
                    new ApiResponse<>(
                            0,
                            "解析成功，前端可先展示 playUrl 和封面，后端继续转写",
                            new DouyinVideoParseWithTranscriptEvent("parsed", parseResult, null),
                            traceId
                    )
            );

            String playUrl = parseResult == null ? null : parseResult.getPlayUrl();
            if (!StringUtils.hasText(playUrl)) {
                throw new BusinessException(50202, "TikHub parse succeeded but playUrl is empty");
            }

            sendEvent(
                    emitter,
                    "transcribing",
                    new ApiResponse<>(
                            0,
                            "正在转写视频文案",
                            new DouyinVideoParseWithTranscriptEvent("transcribing", parseResult, null),
                            traceId
                    )
            );

            WriterVO transcriptResult = writerService.extractDouyinVideoTranscript(new DouyinVideoTranscriptRequest(playUrl));
            sendEvent(
                    emitter,
                    "completed",
                    new ApiResponse<>(
                            0,
                            "转写完成",
                            new DouyinVideoParseWithTranscriptEvent("completed", parseResult, transcriptResult),
                            traceId
                    )
            );
            emitter.complete();
        } catch (Exception exception) {
            ApiResponse<DouyinVideoParseWithTranscriptEvent> errorResponse = new ApiResponse<>(
                    errorCodeOf(exception),
                    exception.getMessage(),
                    new DouyinVideoParseWithTranscriptEvent("error", parseResult, null),
                    traceId
            );
            try {
                sendEvent(emitter, "error", errorResponse);
                emitter.complete();
            } catch (RuntimeException runtimeException) {
                emitter.completeWithError(runtimeException);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, ApiResponse<DouyinVideoParseWithTranscriptEvent> body) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(body, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new RuntimeException("SSE send failed: " + exception.getMessage(), exception);
        }
    }

    private int errorCodeOf(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getCode();
        }
        return 50000;
    }
}
