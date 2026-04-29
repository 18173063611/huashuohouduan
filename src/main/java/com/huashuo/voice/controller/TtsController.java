package com.huashuo.voice.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;
import com.huashuo.voice.service.TtsService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TTS 提交入口；路径与《后端文件代码开发规范》§7 {@code POST /api/v1/voices/tts} 一致。
 */
@Validated
@RestController
@RequestMapping("/api/v1/voices")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping("/tts")
    public ApiResponse<TtsGenerateResponse> generate(@Valid @RequestBody TtsGenerateRequest request) {
        return ApiResponse.success(ttsService.generate(request, traceId()), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
