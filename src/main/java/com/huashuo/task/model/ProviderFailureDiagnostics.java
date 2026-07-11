package com.huashuo.task.model;

import java.time.LocalDateTime;

/**
 * Sanitized provider failure details persisted against a local task.
 */
public record ProviderFailureDiagnostics(
        Integer httpStatus,
        String providerErrorCode,
        String providerErrorMessage,
        String providerResponseRaw,
        String providerTraceId,
        String providerRequestId,
        String providerTaskId,
        Long requestDurationMs,
        String exceptionType,
        String stackTraceSummary,
        String failureStage,
        Boolean responseBodyEmpty,
        LocalDateTime occurredAt
) {
}
