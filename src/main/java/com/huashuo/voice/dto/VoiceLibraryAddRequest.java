package com.huashuo.voice.dto;

import jakarta.validation.constraints.NotNull;

public record VoiceLibraryAddRequest(@NotNull Long voiceId) {
}
