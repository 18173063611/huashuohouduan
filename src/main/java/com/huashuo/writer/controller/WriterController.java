package com.huashuo.writer.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.writer.dto.RewriteDTO;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Executor;

@Validated
@RestController
@RequestMapping("/api/v1/writer")
@Slf4j
public class WriterController {

    private final WriterService writerService;
    private final Executor writerTaskExecutor;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final UserAuthService userAuthService;

    public WriterController(
            WriterService writerService,
            @Qualifier("writerTaskExecutor") Executor writerTaskExecutor,
            TaskService taskService,
            ObjectMapper objectMapper,
            UserAuthService userAuthService
    ) {
        this.writerService = writerService;
        this.writerTaskExecutor = writerTaskExecutor;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.userAuthService = userAuthService;
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
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestBody DouyinVideoParseRequest request
    ) {
        String traceId = traceId();
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;

        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());

        writerTaskExecutor.execute(() -> streamParseWithTranscript(emitter, request, traceId, ownerUserId));
        return emitter;
    }

    @PostMapping("/douyin/rewrite")
    public ApiResponse<WriterVO> rewriteDouyinVideo(@RequestBody RewriteDTO request) {
        return ApiResponse.success(writerService.rewriteDouyinVideo(request), traceId());
    }

    @PostMapping("/douyin/transcript")
    public ApiResponse<WriterVO> extractDouyinVideoTranscript(@RequestBody DouyinVideoTranscriptRequest request) {
        return ApiResponse.success(writerService.extractDouyinVideoTranscript(request), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private void streamParseWithTranscript(
            SseEmitter emitter,
            DouyinVideoParseRequest request,
            String traceId,
            Long ownerUserId
    ) {
        DouyinVideoParseResponse parseResult = null;
        long taskId = 0;
        try {
            String inputJson = toInputJson(request);
            taskId = taskService.createTask(request.getProjectId(), TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT, inputJson, traceId, ownerUserId)
                    .taskId();
            taskService.startTask(taskId);

            parseResult = writerService.parseDouyinVideo(request);
            log.info("parseResult: {}", parseResult.getPlayUrl());
            log.info("解析dy得到视频链接，当前的时间：{}", LocalDateTime.now());
            sendEvent(
                    emitter,
                    "parsed",
                    new ApiResponse<>(
                            0,
                            "解析成功，前端可先展示 playUrl 和封面，后端继续转写",
                            new DouyinVideoParseWithTranscriptEvent("parsed", taskId, parseResult, null),
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
                            new DouyinVideoParseWithTranscriptEvent("transcribing", taskId, parseResult, null),
                            traceId
                    )
            );

            WriterVO transcriptResult = writerService.extractDouyinVideoTranscript(
                    new DouyinVideoTranscriptRequest(playUrl));

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("parseResult", parseResult);
            output.put("transcriptResult", transcriptResult);
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));

            sendEvent(
                    emitter,
                    "completed",
                    new ApiResponse<>(
                            0,
                            "转写完成",
                            new DouyinVideoParseWithTranscriptEvent("completed", taskId, parseResult, transcriptResult),
                            traceId
                    )
            );
            emitter.complete();
        } catch (Exception exception) {
            if (taskId > 0) {
                try {
                    taskService.failTask(taskId, exception.getMessage() == null ? "对标解析或转写失败" : exception.getMessage());
                } catch (Exception taskEx) {
                    log.warn("Failed to mark task {} failed: {}", taskId, taskEx.getMessage());
                }
            }
            ApiResponse<DouyinVideoParseWithTranscriptEvent> errorResponse = new ApiResponse<>(
                    errorCodeOf(exception),
                    exception.getMessage(),
                    new DouyinVideoParseWithTranscriptEvent("error", taskId > 0 ? taskId : null, parseResult, null),
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

    private String toInputJson(DouyinVideoParseRequest request) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", request.getUrl() == null ? "" : request.getUrl());
            if (request.getProjectId() != null) {
                payload.put("projectId", request.getProjectId());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize task input");
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
