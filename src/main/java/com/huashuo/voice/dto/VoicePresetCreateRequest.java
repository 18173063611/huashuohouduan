package com.huashuo.voice.dto;

import jakarta.validation.constraints.NotBlank;

public record VoicePresetCreateRequest(
        @NotBlank(message = "providerVoiceId is required")
        String providerVoiceId,

        @NotBlank(message = "voiceName is required")
        String voiceName,

        String gender,
        String scene,
        String sampleUrl
) {
}
