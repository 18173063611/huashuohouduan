package com.huashuo.voice.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.voice.dto.TtsGenerateRequest;
import com.huashuo.voice.dto.TtsGenerateResponse;
import com.huashuo.voice.dto.TtsTaskDetailResponse;
import com.huashuo.voice.dto.VoiceLibraryAddRequest;
import com.huashuo.voice.dto.VoicePresetCreateRequest;
import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.dto.VoicePresetListResponse;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.voice.service.TtsService;
import com.huashuo.voice.service.VoicePresetService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

/**
 * 文案转音频：音色列表、提交任务、查询任务（与《实现规划》§5.1 一致）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/voices")
public class TtsController {

    private final TtsService ttsService;
    private final VoicePresetService voicePresetService;
    private final UserAuthService userAuthService;

    public TtsController(TtsService ttsService, VoicePresetService voicePresetService,
                          UserAuthService userAuthService) {
        this.ttsService = ttsService;
        this.voicePresetService = voicePresetService;
        this.userAuthService = userAuthService;
    }

    /**
     * 已登录：返回私人音色库（首次访问自动写入三条默认音色）；未登录：返回完整公共目录（与 /catalog 一致）。
     */
    @GetMapping("/presets")
    public ApiResponse<VoicePresetListResponse> listPresets(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        if (viewer.isPresent()) {
            long userId = viewer.getAsLong();
            voicePresetService.ensureDefaultUserLibraryIfFirstVisit(userId);
            return ApiResponse.success(new VoicePresetListResponse(voicePresetService.listUserLibrary(userId)), traceId());
        }
        return ApiResponse.success(new VoicePresetListResponse(voicePresetService.listCatalogPresets()), traceId());
    }

    /** 公共音色目录（浏览「公共音色库」），无需登录。 */
    @GetMapping("/catalog")
    public ApiResponse<VoicePresetListResponse> listCatalog() {
        return ApiResponse.success(new VoicePresetListResponse(voicePresetService.listCatalogPresets()), traceId());
    }

    @PostMapping("/library")
    public ApiResponse<Void> addToLibrary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody VoiceLibraryAddRequest request
    ) {
        long userId = userAuthService.requireUserId(authorization, xAuthToken);
        voicePresetService.addVoiceToUserLibrary(userId, request.voiceId());
        return ApiResponse.success(null, traceId());
    }

    @DeleteMapping("/library/{voiceId}")
    public ApiResponse<Void> removeFromLibrary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long voiceId
    ) {
        long userId = userAuthService.requireUserId(authorization, xAuthToken);
        voicePresetService.removeVoiceFromUserLibrary(userId, voiceId);
        return ApiResponse.success(null, traceId());
    }

    @PostMapping("/presets")
    public ApiResponse<VoicePresetItem> createPreset(@Valid @RequestBody VoicePresetCreateRequest request) {
        return ApiResponse.success(voicePresetService.createPreset(request), traceId());
    }

    @PostMapping("/tts")
    public ApiResponse<TtsGenerateResponse> generate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody TtsGenerateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(ttsService.generate(request, traceId(), ownerUserId), traceId());
    }

    @GetMapping("/tts/{taskId}")
    public ApiResponse<TtsTaskDetailResponse> getTtsTask(@PathVariable Long taskId) {
        return ApiResponse.success(ttsService.getTtsTask(taskId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
