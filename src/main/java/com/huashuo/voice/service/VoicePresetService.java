package com.huashuo.voice.service;

import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.dto.VoicePresetCreateRequest;
import com.huashuo.voice.entity.VoiceProfileEntity;

import java.util.List;

public interface VoicePresetService {

    List<VoicePresetItem> listEnabledPresets();

    /** 公共音色目录（全员可见的 voice_profile），用于未登录或浏览「公共音色库」。 */
    List<VoicePresetItem> listCatalogPresets();

    /**
     * 当前用户私人音色库；会先确保首次访问时写入三条默认音色（清爽女声 / 沉稳男声 / 活力女声）。
     */
    List<VoicePresetItem> listUserLibrary(Long userId);

    void ensureDefaultUserLibraryIfFirstVisit(Long userId);

    void addVoiceToUserLibrary(Long userId, Long voiceId);

    void removeVoiceFromUserLibrary(Long userId, Long voiceId);

    VoicePresetItem createPreset(VoicePresetCreateRequest request);

    VoiceProfileEntity requireEnabled(Long voiceId);

    /** 已登录用户仅可使用其私人库中的音色；未登录仍可使用任意已启用预设。 */
    VoiceProfileEntity requireEnabledForUser(Long voiceId, Long ownerUserId);

    /** 自动 TTS 场景使用：优先取当前用户私人库第一条音色，否则回退公共启用音色。 */
    VoiceProfileEntity resolveDefaultForUser(Long ownerUserId);
}
