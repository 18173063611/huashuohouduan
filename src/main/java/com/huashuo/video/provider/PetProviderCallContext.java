package com.huashuo.video.provider;

import org.springframework.util.StringUtils;

/**
 * Correlates one pet task with all Seedance HTTP calls made for it.
 */
public record PetProviderCallContext(long taskId, String requestTraceId) {

    public PetProviderCallContext {
        if (!StringUtils.hasText(requestTraceId)) {
            requestTraceId = "pet-task-" + taskId;
        } else {
            requestTraceId = requestTraceId.trim();
        }
    }
}
