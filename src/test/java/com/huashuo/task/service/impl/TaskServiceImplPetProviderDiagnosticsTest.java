package com.huashuo.task.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.billing.service.BillingStepConfigService;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.billing.service.UsageEstimateService;
import com.huashuo.task.config.AiTaskProperties;
import com.huashuo.task.config.TaskCreditProperties;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.limit.AiTaskUserRateLimiter;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.model.ProviderFailureDiagnostics;
import com.huashuo.task.service.TaskResultAssetService;
import com.huashuo.task.ws.TaskNotificationService;
import com.huashuo.user.service.CreditService;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceImplPetProviderDiagnosticsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskMapper taskMapper;
    private TaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskService = new TaskServiceImpl(
                objectMapper,
                mock(VoiceProfileMapper.class),
                mock(CreditService.class),
                mock(TaskCreditProperties.class),
                mock(AiTaskProperties.class),
                mock(AiTaskUserRateLimiter.class),
                mock(TaskNotificationService.class),
                mock(UsageEstimateService.class),
                mock(CreditBillingService.class),
                mock(BillingStepConfigService.class),
                mock(BillingEstimateService.class),
                mock(TaskResultAssetService.class)
        );
        ReflectionTestUtils.setField(taskService, "baseMapper", taskMapper);
        when(taskMapper.updateById(any(TaskEntity.class))).thenReturn(1);
    }

    @Test
    void persistsAllDiagnosticColumnsAndQueryableOutputJson() throws Exception {
        TaskEntity entity = task(71L, "pet_creation");
        when(taskMapper.selectById(71L)).thenReturn(entity);
        ProviderFailureDiagnostics diagnostics = diagnostics();

        taskService.recordPetProviderFailureDiagnostics(71L, diagnostics);

        assertThat(entity.getProviderHttpStatus()).isEqualTo(400);
        assertThat(entity.getProviderErrorCode()).isEqualTo("InvalidParameter");
        assertThat(entity.getProviderErrorMessage()).isEqualTo("duration is invalid");
        assertThat(entity.getProviderResponseRaw()).contains("InvalidParameter");
        assertThat(entity.getProviderTraceId()).isEqualTo("pet-trace-71");
        assertThat(entity.getProviderRequestId()).isEqualTo("provider-request-71");
        assertThat(entity.getProviderTaskId()).isEqualTo("provider-task-71");
        assertThat(entity.getProviderDurationMs()).isEqualTo(125L);
        assertThat(entity.getProviderStackTrace()).contains("ArkHttpException");

        JsonNode provider = objectMapper.readTree(entity.getOutputJson()).path("providerDiagnostics");
        assertThat(provider.path("httpStatus").asInt()).isEqualTo(400);
        assertThat(provider.path("providerErrorCode").asText()).isEqualTo("InvalidParameter");
        assertThat(provider.path("providerRequestId").asText()).isEqualTo("provider-request-71");
        assertThat(provider.path("requestDurationMs").asLong()).isEqualTo(125L);
        assertThat(provider.path("failureStage").asText()).isEqualTo("create");
        verify(taskMapper).updateById(entity);
    }

    @Test
    void refusesToWriteDiagnosticsIntoCarTask() {
        TaskEntity entity = task(72L, "car_sales");
        when(taskMapper.selectById(72L)).thenReturn(entity);

        taskService.recordPetProviderFailureDiagnostics(72L, diagnostics());

        assertThat(entity.getProviderErrorCode()).isNull();
        verify(taskMapper, never()).updateById(any(TaskEntity.class));
    }

    private TaskEntity task(long taskId, String businessType) {
        TaskEntity entity = new TaskEntity();
        entity.setTaskId(taskId);
        entity.setTaskType("SEEDANCE_REFERENCE_VIDEO");
        entity.setInputJson("{\"businessType\":\"" + businessType + "\"}");
        entity.setOutputJson("{\"existing\":true}");
        entity.setTraceId("pet-trace-" + taskId);
        return entity;
    }

    private ProviderFailureDiagnostics diagnostics() {
        return new ProviderFailureDiagnostics(
                400,
                "InvalidParameter",
                "duration is invalid",
                "{\"error\":{\"code\":\"InvalidParameter\"}}",
                "pet-trace-71",
                "provider-request-71",
                "provider-task-71",
                125L,
                "com.volcengine.ark.runtime.exception.ArkHttpException",
                "ArkHttpException: duration is invalid",
                "create",
                false,
                LocalDateTime.of(2026, 7, 11, 18, 30)
        );
    }
}
