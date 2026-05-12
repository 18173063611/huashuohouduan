package com.huashuo.voice.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;
import com.huashuo.voice.dto.TtsTaskDetailResponse;
import com.huashuo.voice.dto.VoicePresetListResponse;
import com.huashuo.user.util.CurrentUser;
import com.huashuo.voice.service.TtsService;
import com.huashuo.voice.service.VoicePresetService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文案转音频：音色列表、提交任务、查询任务（与《实现规划》§5.1 一致）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/voices")
public class TtsController {

    private final TtsService ttsService;
    private final VoicePresetService voicePresetService;

    public TtsController(TtsService ttsService, VoicePresetService voicePresetService) {
        this.ttsService = ttsService;
        this.voicePresetService = voicePresetService;
    }

    @GetMapping("/presets")
    public ApiResponse<VoicePresetListResponse> listPresets() {
        return ApiResponse.success(new VoicePresetListResponse(voicePresetService.listEnabledPresets()), traceId());
    }

    @PostMapping("/tts")
    public ApiResponse<TtsGenerateResponse> generate(
            @Valid @RequestBody TtsGenerateRequest request
    ) {
        return ApiResponse.success(ttsService.generate(request, traceId(), CurrentUser.nullableUserId()), traceId());
    }

    @GetMapping("/tts/{taskId}")
    public ApiResponse<TtsTaskDetailResponse> getTtsTask(@PathVariable Long taskId) {
        return ApiResponse.success(ttsService.getTtsTask(taskId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
