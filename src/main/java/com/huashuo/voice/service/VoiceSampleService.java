package com.huashuo.voice.service;

public interface VoiceSampleService {

    /**
     * 获取（或生成并缓存）某个音色的试听音频 URL。
     *
     * @param voiceId voice_profile.voice_id
     * @param text 试听文本；为空时使用默认文本
     */
    String getOrCreateSampleUrl(Long voiceId, String text);
}

