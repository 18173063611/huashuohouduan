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
import com.huashuo.voice.dto.VoiceSampleResponse;
import com.huashuo.voice.dto.VoiceSampleTaskCreateRequest;
import com.huashuo.voice.dto.VoiceSampleTaskCreateResponse;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.voice.service.TtsService;
import com.huashuo.voice.service.VoicePresetService;
import com.huashuo.voice.service.VoiceSampleService;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.voice.job.VoiceSampleTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
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
    private final VoiceSampleService voiceSampleService;
    private final TaskService taskService;
    private final VoiceSampleTaskExecutor voiceSampleTaskExecutor;
    private final ObjectMapper objectMapper;
    private final UserAuthService userAuthService;

    public TtsController(
            TtsService ttsService,
            VoicePresetService voicePresetService,
            VoiceSampleService voiceSampleService,
            TaskService taskService,
            VoiceSampleTaskExecutor voiceSampleTaskExecutor,
            ObjectMapper objectMapper,
            UserAuthService userAuthService
    ) {
        this.ttsService = ttsService;
        this.voicePresetService = voicePresetService;
        this.voiceSampleService = voiceSampleService;
        this.taskService = taskService;
        this.voiceSampleTaskExecutor = voiceSampleTaskExecutor;
        this.objectMapper = objectMapper;
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

    /**
     * 获取或生成试听音频 URL：首次生成后会回写 voice_profile.sample_url 作为缓存。
     * text 为空时使用默认试听文案。
     */
    @GetMapping("/presets/{voiceId}/sample")
    public ApiResponse<VoiceSampleResponse> getSample(
            @PathVariable Long voiceId,
            @org.springframework.web.bind.annotation.RequestParam(value = "text", required = false) String text
    ) {
        String url = voiceSampleService.getOrCreateSampleUrl(voiceId, text);
        return ApiResponse.success(new VoiceSampleResponse(voiceId, url), traceId());
    }

    /**
     * 提交「音色试听」任务：首次会生成并持久化缓存到 TOS（并回写 voice_profile.sample_url），后续会直接读取缓存。
     * 该任务会进入任务中心（可重试）。
     */
    @PostMapping("/presets/{voiceId}/sample/tasks")
    public ApiResponse<VoiceSampleTaskCreateResponse> createSampleTask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @PathVariable Long voiceId,
            @RequestBody(required = false) VoiceSampleTaskCreateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("voiceId", voiceId);
        if (request != null && request.text() != null && !request.text().isBlank()) {
            input.put("text", request.text().trim());
        }
        String inputJson = toJsonSafe(input);

        String idem = trimIdempotencyKey(idempotencyHeader);
        TaskItem task = taskService.createTask(
                null,
                TaskTypeCode.VOICE_SAMPLE,
                inputJson,
                traceId(),
                ownerUserId,
                null,
                null,
                idem
        );
        voiceSampleTaskExecutor.run(task.taskId());
        return ApiResponse.success(new VoiceSampleTaskCreateResponse(task.taskId(), task.status()), traceId());
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody TtsGenerateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        String idem = trimIdempotencyKey(idempotencyHeader);
        return ApiResponse.success(ttsService.generate(request, traceId(), ownerUserId, idem), traceId());
    }

    @GetMapping("/tts/{taskId}")
    public ApiResponse<TtsTaskDetailResponse> getTtsTask(@PathVariable Long taskId) {
        return ApiResponse.success(ttsService.getTtsTask(taskId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private static String trimIdempotencyKey(String header) {
        return StringUtils.hasText(header) ? header.trim() : null;
    }
}
