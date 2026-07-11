package com.huashuo.video.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.model.ProviderFailureDiagnostics;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.provider.PetProviderCallException;
import com.volcengine.ark.runtime.exception.ArkAPIError;
import com.volcengine.ark.runtime.exception.ArkHttpException;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import retrofit2.HttpException;
import retrofit2.Response;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoServiceImplPetProviderObservabilityTest {

    private static final long TASK_ID = 9011L;
    private static final String TRACE_ID = "pet-provider-mock-trace-9011";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ArkService arkService;
    private TaskService taskService;
    private VideoServiceImpl service;

    @BeforeEach
    void setUp() {
        arkService = mock(ArkService.class);
        taskService = mock(TaskService.class);
        SeedanceResourceUrlValidator resourceUrlValidator = mock(SeedanceResourceUrlValidator.class);
        when(resourceUrlValidator.resolveImageUrl(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskService.getTask(TASK_ID)).thenReturn(petTask());
        service = new VideoServiceImpl(
                arkService,
                "doubao-seedance-1-5-pro",
                "doubao-seedance-2-0-fast",
                1,
                60,
                taskService,
                objectMapper,
                null,
                null,
                null,
                null,
                resourceUrlValidator,
                null,
                "https://ark.cn-beijing.volces.com/api/v3",
                "",
                "doubao-seed-2-0-mini-260215",
                4,
                "ffmpeg",
                "ffprobe",
                ""
        );
    }

    @Test
    void capturesHttp400BodyRequestMetadataAndTaskCorrelatedLog() {
        String body = "{\"error\":{\"code\":\"InvalidParameter\",\"message\":\"duration is invalid\","
                + "\"api_key\":\"secret-test-value\"}}";
        when(arkService.createContentGenerationTask(any(CreateContentGenerationTaskRequest.class), anyMap()))
                .thenThrow(arkHttpException(400, "InvalidParameter", "duration is invalid", body,
                        "provider-request-400"));

        Logger logger = (Logger) LoggerFactory.getLogger(VideoServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> service.executeForExistingTask(TASK_ID))
                    .isInstanceOf(PetProviderCallException.class)
                    .hasMessageContaining("duration is invalid")
                    .hasMessageNotContaining("null");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        ProviderFailureDiagnostics diagnostics = capturedDiagnostics();
        assertThat(diagnostics.httpStatus()).isEqualTo(400);
        assertThat(diagnostics.providerErrorCode()).isEqualTo("InvalidParameter");
        assertThat(diagnostics.providerRequestId()).isEqualTo("provider-request-400");
        assertThat(diagnostics.providerResponseRaw()).contains("duration is invalid", "[REDACTED]")
                .doesNotContain("secret-test-value");
        assertThat(diagnostics.requestDurationMs()).isNotNegative();
        assertThat(diagnostics.stackTraceSummary()).contains("ArkHttpException");

        ArgumentCaptor<CreateContentGenerationTaskRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateContentGenerationTaskRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(arkService).createContentGenerationTask(requestCaptor.capture(), headersCaptor.capture());
        assertThat(requestCaptor.getValue().getDuration()).isEqualTo(11L);
        assertThat(requestCaptor.getValue().getRatio()).isEqualTo("9:16");
        assertThat(requestCaptor.getValue().getContent()).hasSize(2);
        assertThat(headersCaptor.getValue()).containsEntry("X-Client-Request-Id", TRACE_ID);
        verify(arkService, never()).createContentGenerationTask(any(CreateContentGenerationTaskRequest.class));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("PET_PROVIDER_CALL_START")
                        && message.contains("taskId=" + TASK_ID)
                        && message.contains(TRACE_ID))
                .anyMatch(message -> message.contains("PET_PROVIDER_CALL_FAILED")
                        && message.contains("taskId=" + TASK_ID));
    }

    @Test
    void capturesHttp500ProviderFailure() {
        when(arkService.createContentGenerationTask(any(CreateContentGenerationTaskRequest.class), anyMap()))
                .thenThrow(arkHttpException(500, "InternalError", "provider overloaded",
                        "{\"error\":{\"code\":\"InternalError\",\"message\":\"provider overloaded\"}}",
                        "provider-request-500"));

        assertThatThrownBy(() -> service.executeForExistingTask(TASK_ID))
                .isInstanceOf(PetProviderCallException.class)
                .hasMessageContaining("provider overloaded");

        ProviderFailureDiagnostics diagnostics = capturedDiagnostics();
        assertThat(diagnostics.httpStatus()).isEqualTo(500);
        assertThat(diagnostics.providerErrorCode()).isEqualTo("InternalError");
        assertThat(diagnostics.providerRequestId()).isEqualTo("provider-request-500");
        assertThat(diagnostics.providerResponseRaw()).contains("provider overloaded");
    }

    @Test
    void capturesTimeoutWithoutNullMessage() {
        ArkAPIError error = arkApiError("", "socket timeout", "");
        ArkHttpException timeout = new ArkHttpException(
                error,
                new SocketTimeoutException("mock provider timed out"),
                500,
                TRACE_ID
        );
        when(arkService.createContentGenerationTask(any(CreateContentGenerationTaskRequest.class), anyMap()))
                .thenThrow(timeout);

        assertThatThrownBy(() -> service.executeForExistingTask(TASK_ID))
                .isInstanceOf(PetProviderCallException.class)
                .hasMessageNotContaining("null");

        ProviderFailureDiagnostics diagnostics = capturedDiagnostics();
        assertThat(diagnostics.providerErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(diagnostics.providerErrorMessage()).contains("socket timeout");
        assertThat(diagnostics.providerTraceId()).isEqualTo(TRACE_ID);
        assertThat(diagnostics.stackTraceSummary()).contains("SocketTimeoutException", "mock provider timed out");
    }

    @Test
    void capturesEmptyProviderBodyAndTaskId() {
        when(arkService.createContentGenerationTask(any(CreateContentGenerationTaskRequest.class), anyMap()))
                .thenReturn(null);

        assertThatThrownBy(() -> service.executeForExistingTask(TASK_ID))
                .isInstanceOf(PetProviderCallException.class)
                .hasMessageContaining("empty response")
                .hasMessageNotContaining("null");

        ProviderFailureDiagnostics diagnostics = capturedDiagnostics();
        assertThat(diagnostics.providerErrorCode()).isEqualTo("PROVIDER_EMPTY_RESPONSE");
        assertThat(diagnostics.providerResponseRaw()).isEmpty();
        assertThat(diagnostics.responseBodyEmpty()).isTrue();
        assertThat(diagnostics.providerTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void replacesNullExceptionMessageWithActionableDiagnostics() {
        when(arkService.createContentGenerationTask(any(CreateContentGenerationTaskRequest.class), anyMap()))
                .thenThrow(new RuntimeException());

        assertThatThrownBy(() -> service.executeForExistingTask(TASK_ID))
                .isInstanceOf(PetProviderCallException.class)
                .hasMessageContaining("Provider request failed")
                .hasMessageNotContaining("null");

        ProviderFailureDiagnostics diagnostics = capturedDiagnostics();
        assertThat(diagnostics.providerErrorCode()).isEqualTo("PROVIDER_CALL_FAILED");
        assertThat(diagnostics.providerErrorMessage()).isEqualTo("Provider request failed");
        assertThat(diagnostics.stackTraceSummary()).contains("<no message>");
    }

    private ProviderFailureDiagnostics capturedDiagnostics() {
        ArgumentCaptor<ProviderFailureDiagnostics> captor = ArgumentCaptor.forClass(ProviderFailureDiagnostics.class);
        verify(taskService).recordPetProviderFailureDiagnostics(eq(TASK_ID), captor.capture());
        return captor.getValue();
    }

    private ArkHttpException arkHttpException(int status, String code, String message, String body,
                                              String providerRequestId) {
        Request request = new Request.Builder().url("http://127.0.0.1/mock-provider/tasks").build();
        Headers headers = new Headers.Builder().add("x-request-id", providerRequestId).build();
        okhttp3.Response rawResponse = new okhttp3.Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("mock provider error")
                .headers(headers)
                .build();
        ResponseBody responseBody = ResponseBody.create(
                MediaType.parse("application/json"),
                body
        );
        HttpException httpException = new HttpException(Response.error(responseBody, rawResponse));
        return new ArkHttpException(arkApiError(code, message, "invalid_request"), httpException, status, TRACE_ID);
    }

    private ArkAPIError arkApiError(String code, String message, String type) {
        ArkAPIError.ArkErrorDetails details = new ArkAPIError.ArkErrorDetails();
        details.setCode(code);
        details.setMessage(message);
        details.setType(type);
        details.setParam("duration");
        return new ArkAPIError(details);
    }

    private TaskItem petTask() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 11, 18, 0);
        String inputJson = "{"
                + "\"imageUrls\":[\"https://assets.example.test/pet.jpg\"],"
                + "\"prompt\":\"A structured pet story\","
                + "\"ratio\":\"9:16\","
                + "\"duration\":11,"
                + "\"generateAudio\":true,"
                + "\"businessType\":\"pet_creation\"}"
                ;
        return new TaskItem(
                TASK_ID, null, 42L, TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                "doubao-seedance-2-0-fast", "VOLCENGINE", null,
                null, null, 280L, null, "PRECHARGED", 280L, null,
                "Pet provider mock", TaskStatusCode.RUNNING, 10, null,
                null, null, 0, false, inputJson, null, TRACE_ID,
                now, now, now, null
        );
    }
}
