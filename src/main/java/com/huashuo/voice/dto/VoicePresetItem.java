package com.huashuo.voice.dto;

public record VoicePresetItem(
        Long voiceId,
        String provider,
        String voiceName,
        String gender,
        String scene,
        String sampleUrl
) {
}
