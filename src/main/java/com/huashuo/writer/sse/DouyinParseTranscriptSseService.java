package com.huashuo.writer.sse;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.writer.pojo.DouyinVideoParseWithTranscriptEvent;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DouyinParseTranscriptSseService {

    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(10).toMillis();

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createAndRegister(Long taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        if (taskId != null) {
            emitters.put(taskId, emitter);
            emitter.onCompletion(() -> emitters.remove(taskId));
            emitter.onTimeout(() -> emitters.remove(taskId));
            emitter.onError(error -> emitters.remove(taskId));
        }
        return emitter;
    }

    public void send(Long taskId, String eventName, DouyinVideoParseWithTranscriptEvent event, String message) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(new ApiResponse<>(0, message, event, traceId()), MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new RuntimeException("SSE send failed: " + exception.getMessage(), exception);
        }
    }

    public void sendError(Long taskId, Exception exception, DouyinVideoParseWithTranscriptEvent event) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }
        ApiResponse<DouyinVideoParseWithTranscriptEvent> body = new ApiResponse<>(
                errorCodeOf(exception),
                exception.getMessage(),
                event,
                traceId()
        );
        try {
            emitter.send(SseEmitter.event().name("error").data(body, MediaType.APPLICATION_JSON));
        } catch (IOException sendException) {
            throw new RuntimeException("SSE send failed: " + sendException.getMessage(), sendException);
        }
    }

    public void complete(Long taskId) {
        SseEmitter emitter = emitters.remove(taskId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    public void completeWithError(Long taskId, Exception exception) {
        SseEmitter emitter = emitters.remove(taskId);
        if (emitter != null) {
            emitter.completeWithError(exception);
        }
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private int errorCodeOf(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getCode();
        }
        return 50000;
    }
}
