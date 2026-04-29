package com.huashuo.voice.service;

import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;

/**
 * 语音服务接口：定义脚本文案转语音的任务入口。
 */
public interface TtsService {

    TtsGenerateResponse generate(TtsGenerateRequest request, String traceId);
}
