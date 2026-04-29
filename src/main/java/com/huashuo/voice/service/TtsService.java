package com.huashuo.voice.service;

import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;

public interface TtsService {

    TtsGenerateResponse generate(TtsGenerateRequest request, String traceId);
}
