package com.huashuo.voice.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 文案转音频；{@code scriptId} 为脚本版本主键，与表 {@code script_version.script_version_id} 一致。
 */
public record TtsGenerateRequest(
        Long projectId,
        Long scriptId,
        String text,
        @NotNull Long voiceId,
        String provider,
        Double speed,
        Integer pitch,
        Double volume,
        String businessDomain
) {
}
