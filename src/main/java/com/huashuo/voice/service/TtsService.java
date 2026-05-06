package com.huashuo.voice.service;

import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;
import com.huashuo.voice.dto.TtsTaskDetailResponse;

public interface TtsService {

    TtsGenerateResponse generate(TtsGenerateRequest request, String traceId);

    TtsTaskDetailResponse getTtsTask(Long taskId);
}
