package com.huashuo.voice.service;

import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.dto.VoicePresetCreateRequest;
import com.huashuo.voice.entity.VoiceProfileEntity;

import java.util.List;

public interface VoicePresetService {

    List<VoicePresetItem> listEnabledPresets();

    VoicePresetItem createPreset(VoicePresetCreateRequest request);

    VoiceProfileEntity requireEnabled(Long voiceId);
}
