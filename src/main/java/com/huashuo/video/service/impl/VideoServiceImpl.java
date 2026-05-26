package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.service.VideoService;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.AudioUrl;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.Content;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.ImageUrl;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskResult;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskResponse;
import com.volcengine.ark.runtime.service.ArkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Seedance 视频生成的 ARK-only 实现：仅负责调用方舟 API + 同步轮询 + 真实用量回写，
 * 任务台账（createTask / startTask / completeTask / failTask）由 controller 与 MQ executor 自行管理。
 *
 * <p>历史上本类同时承担了「同步创建本地任务 + 调 ARK + 结算」三件事，而 {@code SeedanceVideoTaskExecutor}
 * 又会再次进入 {@code generateText(dto)} 的兜底 default 方法，导致一条任务被建两次（第二次匿名预扣失败、
 * 错误信息写回原任务）。本次重构后入口收敛为 {@link #executeForExistingTask(long)}：根据 taskId 派发
 * 到对应端点的 ARK 私有方法 {@code doGenerateXxx}，完成后调用 {@code creditBillingService.settle/recordActual}
 * 写一次真实用量。</p>
 */
@Service
@Slf4j
public class VideoServiceImpl implements VideoService {

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_IMAGE_URL = "image_url";
    private static final String TYPE_AUDIO_URL = "audio_url";

    private static final String ROLE_FIRST_FRAME = "first_frame";
    private static final String ROLE_LAST_FRAME = "last_frame";
    private static final String ROLE_REFERENCE_IMAGE = "reference_image";
    private static final String ROLE_REFERENCE_AUDIO = "reference_audio";
    private static final String AUDIO_MODE_NONE = "none";
    private static final String AUDIO_MODE_POST_MIX = "post_mix";
    private static final String AUDIO_MODE_REFERENCE = "reference";
    private static final List<String> STORYBOARD_IGNORED_FIELDS =
            List.of("content", "voiceText", "backgroundMusic");

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";
    private static final String ARK_ERROR_MESSAGE_FIELD = "message=";
    private static final String SEEDANCE_2_MODEL = "ep-20260512233524-85r4g";
    private static final Pattern ARK_REQUEST_ID_SUFFIX_PATTERN =
            Pattern.compile("(?i)\\s*Request\\s*id\\s*[:：].*$");

    private final ArkService arkService;
    private final String defaultModel;
    private final String referenceModel;
    private final long pollIntervalMillis;
    private final long pollTimeoutMillis;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;
    private final StorageService storageService;
    private final AssetService assetService;
    private final String ffmpegPath;
    private final HttpClient httpClient;

    public VideoServiceImpl(
            ArkService seedanceArkService,
            @Value("${volcengine.seedance.model:doubao-seedance-1-5-pro}") String defaultModel,
            @Value("${volcengine.seedance.reference-model:doubao-seedance-1-0-lite-i2v-250428}") String referenceModel,
            @Value("${volcengine.seedance.poll-interval-seconds:5}") long pollIntervalSeconds,
            @Value("${volcengine.seedance.poll-timeout-seconds:600}") long pollTimeoutSeconds,
            TaskService taskService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService,
            StorageService storageService,
            AssetService assetService,
            @Value("${video.stitch.ffmpeg-path:ffmpeg}") String ffmpegPath
    ) {
        this.arkService = seedanceArkService;
        this.defaultModel = defaultModel;
        this.referenceModel = referenceModel;
        this.pollIntervalMillis = Math.max(1L, pollIntervalSeconds) * 1000L;
        this.pollTimeoutMillis = Math.max(60L, pollTimeoutSeconds) * 1000L;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
        this.storageService = storageService;
        this.assetService = assetService;
        this.ffmpegPath = StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public VideoTaskVO executeForExistingTask(long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (task == null) {
            throw new BusinessException(40400, "任务不存在 taskId=" + taskId);
        }
        String taskType = task.taskType();
        String inputJson = task.inputJson();
        if (!StringUtils.hasText(inputJson)) {
            throw new BusinessException(40000, "任务 inputJson 为空 taskId=" + taskId);
        }

        VideoTaskVO arkResult;
        String resolvedModel;
        Integer requestedDuration;
        boolean resultAlreadyStored = false;
        try {
            if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)) {
                TextDTO dto = objectMapper.readValue(inputJson, TextDTO.class);
                if (dto == null || !StringUtils.hasText(dto.getPrompt())) {
                    throw new BusinessException(40000, "prompt 不能为空");
                }
                resolvedModel = pickModel(dto.getModel(), task.modelCode(), defaultModel);
                requestedDuration = dto.getDuration();
                arkResult = doGenerateText(dto, resolvedModel);
            } else if (TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)) {
                ImageDTO dto = objectMapper.readValue(inputJson, ImageDTO.class);
                if (dto == null || !StringUtils.hasText(dto.getImageUrl())) {
                    throw new BusinessException(40000, "imageUrl 不能为空");
                }
                resolvedModel = pickModel(dto.getModel(), task.modelCode(), defaultModel);
                requestedDuration = dto.getDuration();
                arkResult = doGenerateFirstFrame(dto, resolvedModel);
            } else if (TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)) {
                ImageFirstLastFrameDTO dto = objectMapper.readValue(inputJson, ImageFirstLastFrameDTO.class);
                if (dto == null
                        || !StringUtils.hasText(dto.getFirstFrameUrl())
                        || !StringUtils.hasText(dto.getLastFrameUrl())) {
                    throw new BusinessException(40000, "firstFrameUrl 与 lastFrameUrl 均不能为空");
                }
                resolvedModel = pickModel(dto.getModel(), task.modelCode(), defaultModel);
                requestedDuration = dto.getDuration();
                arkResult = doGenerateFirstLastFrame(dto, resolvedModel);
            } else if (TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)) {
                ImageReferenceDTO dto = objectMapper.readValue(inputJson, ImageReferenceDTO.class);
                if (dto == null || dto.getImageUrls() == null || dto.getImageUrls().isEmpty()) {
                    throw new BusinessException(40000, "imageUrls 不能为空");
                }
                resolvedModel = pickModel(dto.getModel(), task.modelCode(), referenceModel);
                requestedDuration = dto.getDuration();
                arkResult = doGenerateReference(dto, resolvedModel);
            } else if (TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(taskType)) {
                CarSalesVideoDTO dto = objectMapper.readValue(inputJson, CarSalesVideoDTO.class);
                if (dto == null || dto.getCarImageUrls() == null || dto.getCarImageUrls().isEmpty()) {
                    throw new BusinessException(40000, "carImageUrls 不能为空");
                }
                resolvedModel = pickModel(dto.getModel(), task.modelCode(), referenceModel);
                requestedDuration = normalizeSegmentCount(dto.getSegmentCount())
                        * normalizeSegmentDuration(dto.getSegmentDuration(), resolvedModel);
                arkResult = doGenerateCarSalesVideo(task, dto, resolvedModel, inputJson);
                resultAlreadyStored = true;
            } else {
                throw new BusinessException(40000, "Unsupported Seedance video task type: " + taskType);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (JsonProcessingException jpe) {
            throw new BusinessException(50000, "任务 inputJson 解析失败：" + jpe.getMessage());
        } catch (Exception ex) {
            log.error("Seedance Ark 调用异常 taskId={}", taskId, ex);
            throw new BusinessException(50100, "视频生成失败：" + ex.getMessage());
        }

        if (arkResult == null) {
            throw new BusinessException(50100, "视频生成失败：Ark 未返回任务结果");
        }
        arkResult.setLocalTaskId(taskId);
        BigDecimal resolvedDuration = resolveDurationSeconds(arkResult, requestedDuration);
        if (resolvedDuration != null) {
            arkResult.setDurationSeconds(resolvedDuration);
        }
        if (!resultAlreadyStored) {
            AssetItem resultAsset = saveSeedanceVideoAsset(task, arkResult, resolvedModel, inputJson);
            if (resultAsset != null) {
                arkResult.setVideoUrl(resultAsset.fileUrl());
                arkResult.setResultAssetId(resultAsset.assetId());
            }
        }
        recordOrSettleActual(taskId, arkResult, resolvedModel, resolvedDuration);
        return arkResult;
    }

    private String pickModel(String dtoModel, String taskModel, String fallback) {
        if (StringUtils.hasText(dtoModel)) {
            return dtoModel;
        }
        if (StringUtils.hasText(taskModel)) {
            return taskModel;
        }
        return fallback;
    }

    /**
     * 优先取 Ark 响应中的真实时长（当前 SDK 暂未暴露），其次取用户请求的 duration 字段。
     * 都拿不到时返回 {@code null} 表示「拿不到真实视频时长」。
     */
    private BigDecimal resolveDurationSeconds(VideoTaskVO arkResult, Integer requestedDurationSeconds) {
        if (arkResult != null && arkResult.getDurationSeconds() != null
                && arkResult.getDurationSeconds().signum() > 0) {
            return arkResult.getDurationSeconds();
        }
        if (requestedDurationSeconds != null && requestedDurationSeconds > 0) {
            return BigDecimal.valueOf(requestedDurationSeconds);
        }
        return null;
    }

    /**
     * 真实用量回写路径：
     * <ul>
     *   <li>{@code durationSeconds > 0}：调用 {@link CreditBillingService#settle}
     *       走完整结算（含 ai_usage_log ACTUAL 行、task.actual_usage 与 actual_credit_cost、settlement_status 流转）。</li>
     *   <li>{@code durationSeconds} 缺失：调用 {@link CreditBillingService#recordActual}
     *       仅写一条 ACTUAL 占位行并同步 task.actual_usage（保持 settlement_status=PRECHARGED，不补扣不退款）。</li>
     * </ul>
     * 任意异常都不影响业务返回——仅打 warn 日志，避免覆盖 Seedance 成功结果。
     */
    private void recordOrSettleActual(Long localTaskId, VideoTaskVO arkResult, String modelCodeRequested,
                                      BigDecimal durationSeconds) {
        if (localTaskId == null || arkResult == null) {
            return;
        }
        String providerForLog = "VOLCENGINE";
        String modelCodeForLog = StringUtils.hasText(arkResult.getModel()) ? arkResult.getModel() : modelCodeRequested;
        String rawJson = toJson(arkResult);

        if (durationSeconds != null && durationSeconds.signum() > 0) {
            UsageActualResult actual = new UsageActualResult(
                    providerForLog,
                    modelCodeForLog,
                    UsageUnit.SECOND,
                    null,
                    arkResult.getCompletionTokens(),
                    arkResult.getCompletionTokens(),
                    null,
                    null,
                    durationSeconds,
                    null,
                    null,
                    rawJson
            );
            try {
                creditBillingService.settle(localTaskId, actual);
            } catch (Exception settleEx) {
                log.warn("Seedance settle 失败 localTaskId={} reason={}", localTaskId, settleEx.getMessage());
            }
            return;
        }

        UsageActualResult placeholder = new UsageActualResult(
                providerForLog,
                modelCodeForLog,
                UsageUnit.SECOND,
                null,
                arkResult.getCompletionTokens(),
                arkResult.getCompletionTokens(),
                null,
                null,
                null,
                null,
                null,
                rawJson
        );
        try {
            creditBillingService.recordActual(localTaskId, placeholder);
        } catch (Exception placeholderEx) {
            log.warn("Seedance recordActual 失败 localTaskId={} reason={}", localTaskId, placeholderEx.getMessage());
        }
    }

    private AssetItem saveSeedanceVideoAsset(TaskItem task, VideoTaskVO arkResult, String resolvedModel,
                                             String inputJson) {
        if (task == null || arkResult == null || !StringUtils.hasText(arkResult.getVideoUrl())) {
            return null;
        }
        if (task.ownerUserId() == null) {
            throw new BusinessException(40100, "生成视频保存到私有资产失败：缺少登录用户信息");
        }

        String originalVideoUrl = arkResult.getVideoUrl().trim();
        String remoteTaskId = StringUtils.hasText(arkResult.getTaskId())
                ? arkResult.getTaskId().trim()
                : String.valueOf(task.taskId());
        String fileName = "seedance-video-" + task.taskId() + "-" + sanitizeName(remoteTaskId) + ".mp4";
        UploadResult stored = null;
        String storageMode = "OBJECT_STORAGE";
        try {
            stored = downloadAndStore(originalVideoUrl, fileName);
        } catch (RuntimeException ex) {
            storageMode = "EXTERNAL_URL";
            log.warn("Seedance 视频保存到对象存储失败，改为登记外部 URL。taskId={}, reason={}", task.taskId(), ex.getMessage());
        }

        return assetService.createGeneratedVideoAsset(
                task.ownerUserId(),
                task.projectId(),
                task.taskId(),
                stored == null ? fileName : stored.filename(),
                stored == null ? "external-url:" + task.taskId() : stored.objectKey(),
                stored == null ? originalVideoUrl : stored.url(),
                arkResult.getLastFrameUrl(),
                stored == null ? "video/mp4" : stored.contentType(),
                stored == null ? 0L : stored.size(),
                task.taskType(),
                buildSeedanceAssetMetadata(task, arkResult, resolvedModel, inputJson, originalVideoUrl, storageMode)
        );
    }

    private UploadResult downloadAndStore(String videoUrl, String fileName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(videoUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50100, "Failed to download Seedance video, HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("video/mp4");
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream in = response.body()) {
                return storageService.upload(in, contentLength, fileName, contentType, "video");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "Failed to store Seedance video: " + e.getMessage());
        }
    }

    private String buildSeedanceAssetMetadata(TaskItem task, VideoTaskVO arkResult, String resolvedModel,
                                              String inputJson, String originalVideoUrl, String storageMode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "VOLCENGINE");
        meta.put("source", "SEEDANCE");
        meta.put("taskType", task.taskType());
        meta.put("localTaskId", task.taskId());
        meta.put("seedanceTaskId", arkResult.getTaskId());
        meta.put("model", StringUtils.hasText(arkResult.getModel()) ? arkResult.getModel() : resolvedModel);
        meta.put("status", arkResult.getStatus());
        meta.put("durationSeconds", arkResult.getDurationSeconds());
        meta.put("completionTokens", arkResult.getCompletionTokens());
        meta.put("lastFrameUrl", arkResult.getLastFrameUrl());
        meta.put("originalVideoUrl", originalVideoUrl);
        meta.put("storageMode", storageMode);
        meta.put("input", parseJsonOrRaw(inputJson));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"provider\":\"VOLCENGINE\",\"source\":\"SEEDANCE\"}";
        }
    }

    private Object parseJsonOrRaw(String inputJson) {
        if (!StringUtils.hasText(inputJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(inputJson);
        } catch (Exception e) {
            return inputJson;
        }
    }

    private String sanitizeName(String value) {
        if (!StringUtils.hasText(value)) {
            return "result";
        }
        String safe = value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    // ---- 原 Ark 调用逻辑（保持不变） ----

    private VideoTaskVO doGenerateText(TextDTO request, String model) {
        List<Content> contents = new ArrayList<>();
        contents.add(buildText(request.getPrompt()));
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req);
    }

    private VideoTaskVO doGenerateFirstFrame(ImageDTO request, String model) {
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        contents.add(buildImage(request.getImageUrl(), ROLE_FIRST_FRAME));
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req);
    }

    private VideoTaskVO doGenerateFirstLastFrame(ImageFirstLastFrameDTO request, String model) {
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        contents.add(buildImage(request.getFirstFrameUrl(), ROLE_FIRST_FRAME));
        contents.add(buildImage(request.getLastFrameUrl(), ROLE_LAST_FRAME));
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req);
    }

    private VideoTaskVO doGenerateReference(ImageReferenceDTO request, String model) {
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        for (String url : request.getImageUrls()) {
            if (StringUtils.hasText(url)) {
                contents.add(buildImage(url, ROLE_REFERENCE_IMAGE));
            }
        }
        if (request.getAudioUrls() != null) {
            for (String url : request.getAudioUrls()) {
                if (StringUtils.hasText(url)) {
                    contents.add(buildAudio(url, ROLE_REFERENCE_AUDIO));
                }
            }
        }
        // 参照图场景不支持 cameraFixed，故不传该字段（保留原默认 false 由 baseBuilder 兜底）。
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req);
    }

    private VideoTaskVO doGenerateCarSalesVideo(TaskItem task, CarSalesVideoDTO request, String model, String inputJson) {
        SanitizedStoryboard sanitizedContext = sanitizeStoryboardText(request.getScriptContext());
        ensureNoStoryboardPollution(sanitizedContext.text());
        request.setScriptContext(sanitizedContext.text());
        List<CarSalesVideoDTO.Scene> scenes = resolveCarSalesScenes(request, model);
        boolean referenceAudio = shouldReferenceAudio(request);
        if (referenceAudio && !isSeedance2(model)) {
            throw new BusinessException(40000, "参考音频生成仅支持 seedance2.0");
        }
        if (referenceAudio && scenes.size() != 1) {
            throw new BusinessException(40000, "参考音频生成当前仅支持 1 段视频，多段成片请使用后期口播配音，BGM 请单独选择");
        }
        List<VideoTaskVO> segmentVideos = new ArrayList<>();
        List<Long> segmentAssetIds = new ArrayList<>();
        List<Path> segmentFiles = new ArrayList<>();
        BigDecimal totalDuration = BigDecimal.ZERO;
        int totalTokens = 0;
        boolean useSeedance2Reference = isSeedance2(model);
        boolean useFinalVoiceAudio = shouldUseFinalAudio(request);
        boolean hasBgm = StringUtils.hasText(request.getBgmUrl());
        boolean generateNativeAudio = referenceAudio || (!useFinalVoiceAudio && !hasBgm);

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("car-sales-video-" + task.taskId() + "-");
            int index = 1;
            for (CarSalesVideoDTO.Scene scene : scenes) {
                List<String> sceneImages = resolveSceneImages(request, scene);
                SanitizedStoryboard sanitizedScene = sanitizeStoryboardText(resolveSceneVisualPrompt(scene));
                ensureNoStoryboardPollution(sanitizedScene.text());
                applySanitizedScenePrompt(scene, sanitizedScene.text());
                Set<String> ignoredFields = new LinkedHashSet<>(sanitizedContext.ignoredFields());
                if (request.getIgnoredStoryboardFields() != null) {
                    ignoredFields.addAll(request.getIgnoredStoryboardFields());
                }
                ignoredFields.addAll(sanitizedScene.ignoredFields());
                if (hasSelectedVoiceAudio(request) && scene != null && StringUtils.hasText(scene.getVoiceText())) {
                    ignoredFields.add("voiceText");
                }
                String scenePrompt = buildCarSalesScenePrompt(request, scene, index, scenes.size(), model);
                int segmentDuration = normalizeSegmentDuration(scene == null ? null : scene.getDuration(), model);
                Map<String, Object> diagnostics = buildCarSalesSeedanceDiagnostics(task, request, model, index,
                        scenePrompt, sanitizedContext.text(), ignoredFields, useSeedance2Reference, referenceAudio);
                log.info("Seedance car sales generation params taskId={} segment={} diagnostics={}",
                        task.taskId(), index, toJson(diagnostics));
                Object segmentRequest;
                VideoTaskVO segment;
                if (useSeedance2Reference) {
                    ImageReferenceDTO referenceRequest = new ImageReferenceDTO();
                    referenceRequest.setImageUrls(sceneImages);
                    referenceRequest.setPrompt(scenePrompt);
                    referenceRequest.setDuration(segmentDuration);
                    referenceRequest.setModel(model);
                    referenceRequest.setGenerateAudio(generateNativeAudio);
                    if (referenceAudio) {
                        referenceRequest.setAudioUrls(List.of(request.getAudioUrl().trim()));
                    }
                    segmentRequest = referenceRequest;
                    segment = doGenerateReference(referenceRequest, model);
                } else {
                    ImageDTO firstFrameRequest = new ImageDTO();
                    firstFrameRequest.setImageUrl(sceneImages.get(0));
                    firstFrameRequest.setPrompt(scenePrompt);
                    firstFrameRequest.setDuration(segmentDuration);
                    firstFrameRequest.setModel(model);
                    firstFrameRequest.setGenerateAudio(generateNativeAudio);
                    segmentRequest = firstFrameRequest;
                    segment = doGenerateFirstFrame(firstFrameRequest, model);
                }
                segment.setLocalTaskId(task.taskId());
                BigDecimal duration = resolveDurationSeconds(segment, segmentDuration);
                segment.setDurationSeconds(duration);
                if (duration != null) {
                    totalDuration = totalDuration.add(duration);
                }
                if (segment.getCompletionTokens() != null) {
                    totalTokens += segment.getCompletionTokens();
                }

                Path segmentFile = tempDir.resolve("segment-" + index + ".mp4");
                downloadVideoToFile(segment.getVideoUrl(), segmentFile);
                segmentFiles.add(segmentFile);

                String segmentInputJson = buildCarSalesSegmentInputJson(request, scene, index, segmentRequest, diagnostics);
                AssetItem segmentAsset = saveSeedanceVideoAsset(task, segment, model, segmentInputJson);
                if (segmentAsset != null) {
                    segment.setVideoUrl(segmentAsset.fileUrl());
                    segment.setResultAssetId(segmentAsset.assetId());
                    segmentAssetIds.add(segmentAsset.assetId());
                }
                segmentVideos.add(segment);
                index++;
            }

            Path finalFile = tempDir.resolve("car-sales-final-" + task.taskId() + ".mp4");
            stitchVideoSegments(segmentFiles, finalFile);
            Path voicedVideoFile = applyCustomAudioIfPresent(useFinalVoiceAudio ? request.getAudioUrl() : null,
                    finalFile, tempDir, task.taskId());
            Path finalVideoFile = applyBgmIfPresent(request.getBgmUrl(), voicedVideoFile, tempDir, task.taskId(),
                    useFinalVoiceAudio || generateNativeAudio);
            AssetItem finalAsset = saveCarSalesFinalAsset(task, request, finalVideoFile, model, inputJson,
                    segmentVideos, segmentAssetIds, totalDuration, totalTokens);

            long now = System.currentTimeMillis() / 1000L;
            return VideoTaskVO.builder()
                    .taskId("car-sales-" + task.taskId())
                    .localTaskId(task.taskId())
                    .model(model)
                    .status(STATUS_SUCCEEDED)
                    .createdAt(now)
                    .updatedAt(now)
                    .videoUrl(finalAsset.fileUrl())
                    .resultAssetId(finalAsset.assetId())
                    .finalAssetId(finalAsset.assetId())
                    .segmentVideos(segmentVideos)
                    .segmentAssetIds(segmentAssetIds)
                    .segmentCount(segmentVideos.size())
                    .durationSeconds(totalDuration)
                    .totalDurationSeconds(totalDuration)
                    .completionTokens(totalTokens)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "汽车销售成片生成失败：" + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private List<CarSalesVideoDTO.Scene> resolveCarSalesScenes(CarSalesVideoDTO request, String model) {
        int count = normalizeSegmentCount(request.getSegmentCount());
        List<CarSalesVideoDTO.Scene> provided = request.getScenes();
        if (provided != null && !provided.isEmpty()) {
            List<CarSalesVideoDTO.Scene> scenes = new ArrayList<>();
            for (CarSalesVideoDTO.Scene scene : provided) {
                if (scene != null && (StringUtils.hasText(scene.getVisualPrompt())
                        || StringUtils.hasText(scene.getPrompt())
                        || StringUtils.hasText(scene.getTitle()))) {
                    scenes.add(scene);
                }
                if (scenes.size() >= 6) {
                    break;
                }
            }
            if (!scenes.isEmpty()) {
                return scenes;
            }
        }

        String[] titles = {"外观开场", "内饰空间", "核心卖点", "到店转化", "用车场景", "优惠收口"};
        String[] prompts = {
                "用高级汽车广告开场展示整车外观、车头、车身线条和灯光质感，镜头稳定推进，突出第一眼吸引力。",
                "展示内饰、座椅、空间、屏幕和储物细节，强调舒适、质感和家庭/通勤使用体验。",
                "围绕动力、智能、安全、油耗/续航或配置亮点做节奏感展示，画面干净有销售说服力。",
                "用门店交付、试驾邀约、权益政策和咨询引导收尾，适合短视频平台汽车销售转化。",
                "展示城市通勤、家庭出行或周末短途场景，让车辆与真实生活需求结合。",
                "再次展示车身高光细节和优惠信息氛围，强化立即咨询和预约试驾。"
        };
        List<CarSalesVideoDTO.Scene> scenes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
            scene.setSegmentIndex(i + 1);
            scene.setTitle(titles[i]);
            scene.setVisualPrompt(prompts[i]);
            scene.setPrompt(prompts[i]);
            scene.setDuration(normalizeSegmentDuration(request.getSegmentDuration(), model));
            scenes.add(scene);
        }
        return scenes;
    }

    private List<String> resolveSceneImages(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene) {
        List<String> urls = scene == null ? null : scene.getImageUrls();
        if (urls == null || urls.isEmpty()) {
            urls = request.getCarImageUrls();
        }
        List<String> clean = new ArrayList<>();
        String hostImageUrl = StringUtils.hasText(request.getHostImageUrl()) ? request.getHostImageUrl().trim() : null;
        int maxVehicleRefs = StringUtils.hasText(hostImageUrl) ? 8 : 9;
        for (String url : urls) {
            if (StringUtils.hasText(url)) {
                clean.add(url.trim());
            }
            if (clean.size() >= maxVehicleRefs) {
                break;
            }
        }
        if (StringUtils.hasText(hostImageUrl) && clean.stream().noneMatch(hostImageUrl::equals)) {
            clean.add(hostImageUrl);
        }
        if (clean.isEmpty()) {
            throw new BusinessException(40000, "每个 scene 至少需要 1 张车辆图片");
        }
        return clean;
    }

    private String resolveSceneVisualPrompt(CarSalesVideoDTO.Scene scene) {
        if (scene == null) {
            return null;
        }
        if (StringUtils.hasText(scene.getVisualPrompt())) {
            return scene.getVisualPrompt();
        }
        return scene.getPrompt();
    }

    private void applySanitizedScenePrompt(CarSalesVideoDTO.Scene scene, String visualPrompt) {
        if (scene == null) {
            return;
        }
        scene.setVisualPrompt(visualPrompt);
        scene.setPrompt(visualPrompt);
    }

    private record SanitizedStoryboard(String text, List<String> ignoredFields) {
    }

    private SanitizedStoryboard sanitizeStoryboardText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new SanitizedStoryboard(null, List.of());
        }
        Set<String> ignoredFields = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(raw);
            String visualText = extractStoryboardVisualText(root, ignoredFields);
            if (StringUtils.hasText(visualText)) {
                return new SanitizedStoryboard(trimPrompt(visualText, 3000), List.copyOf(ignoredFields));
            }
        } catch (Exception ignored) {
            // 非 JSON 文本走轻量关键词清洗，保留用户写的画面描述。
        }

        String sanitized = raw;
        for (String field : STORYBOARD_IGNORED_FIELDS) {
            Pattern pattern = Pattern.compile("(?is)[\"']?" + Pattern.quote(field)
                    + "[\"']?\\s*[:：]\\s*(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^,，。\\n\\r}]+)");
            Matcher matcher = pattern.matcher(sanitized);
            if (matcher.find()) {
                ignoredFields.add(field);
                sanitized = matcher.replaceAll("");
            }
        }
        return new SanitizedStoryboard(trimPrompt(sanitized.trim(), 3000), List.copyOf(ignoredFields));
    }

    private void ensureNoStoryboardPollution(String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String field : STORYBOARD_IGNORED_FIELDS) {
            Pattern pattern = Pattern.compile("(?i)[\"']?" + Pattern.quote(field) + "[\"']?\\s*[:：]");
            if (pattern.matcher(value).find()) {
                throw new BusinessException(40000,
                        "分镜内容包含会污染口播的字段 " + field + "，请重新选择分镜或使用清洗后的画面描述");
            }
        }
    }

    private String extractStoryboardVisualText(JsonNode root, Set<String> ignoredFields) {
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode scenesNode = root.isArray() ? root : firstArray(root, "scripts", "shots", "scenes");
        if (scenesNode == null || !scenesNode.isArray()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (JsonNode node : scenesNode) {
            if (node == null || !node.isObject()) {
                index++;
                continue;
            }
            collectIgnoredFields(node, ignoredFields);
            String order = firstText(node, "order", "segmentIndex");
            String time = firstText(node, "time", "duration", "range");
            String visual = firstText(node, "page", "visualPrompt", "visual", "scene", "shot", "picture", "prompt");
            String highlight = firstText(node, "highlight", "intent", "goal");
            if (!StringUtils.hasText(visual) && !StringUtils.hasText(highlight)) {
                index++;
                continue;
            }
            List<String> parts = new ArrayList<>();
            parts.add("镜头" + (StringUtils.hasText(order) ? order : index));
            if (StringUtils.hasText(time)) {
                parts.add("时间 " + time.trim());
            }
            if (StringUtils.hasText(visual)) {
                parts.add("画面 " + visual.trim());
            }
            if (StringUtils.hasText(highlight)) {
                parts.add("重点 " + highlight.trim());
            }
            lines.add(String.join("；", parts));
            index++;
        }
        return String.join("\n", lines);
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && child.isArray()) {
                return child;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child == null || child.isNull()) {
                continue;
            }
            if (child.isTextual()) {
                String value = child.asText();
                if (StringUtils.hasText(value)) {
                    return value;
                }
            } else if (child.isNumber()) {
                return child.asText();
            }
        }
        return null;
    }

    private void collectIgnoredFields(JsonNode node, Set<String> ignoredFields) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectIgnoredFields(child, ignoredFields);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.fieldNames().forEachRemaining(field -> {
            JsonNode child = node.get(field);
            if (STORYBOARD_IGNORED_FIELDS.contains(field) && child != null && !child.isNull()
                    && (!child.isTextual() || StringUtils.hasText(child.asText()))) {
                ignoredFields.add(field);
            }
            collectIgnoredFields(child, ignoredFields);
        });
    }

    private String buildCarSalesScenePrompt(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene,
                                            int index, int total, String model) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("生成汽车销售短视频第 ").append(index).append("/").append(total).append(" 段。");
        appendPromptLine(prompt, "车型", request.getBrandModel());
        appendPromptLine(prompt, "目标客户", request.getAudience());
        appendPromptLine(prompt, "卖点", request.getSellingPoints());
        appendPromptLine(prompt, "转化引导", request.getCallToAction());
        if (scene != null) {
            appendPromptLine(prompt, "本段主题", scene.getTitle());
            appendPromptLine(prompt, "本段画面", resolveSceneVisualPrompt(scene));
            if (!hasSelectedVoiceAudio(request)) {
                appendPromptLine(prompt, "本段口播文案", scene.getVoiceText());
            }
        }
        appendPromptLine(prompt, "画面分镜参考", request.getScriptContext());
        appendPromptLine(prompt, "补充要求", request.getPrompt());
        if (shouldReferenceAudio(request)) {
            prompt.append("口播、口型、字幕和节奏必须以参考音频为准，不要根据分镜或对标文案重新生成台词。");
        } else if (shouldUseFinalAudio(request)) {
            prompt.append("最终会使用已选择的口播音频替换音轨；当前只生成画面，不要生成字幕文字、台词口型或额外旁白。");
        } else if (StringUtils.hasText(request.getBgmUrl())) {
            prompt.append("最终会单独混入背景音乐；当前只生成画面，不要把 BGM 当作口播或字幕来源。");
        }
        if (StringUtils.hasText(request.getHostImageUrl())) {
            prompt.append("已提供数字人形象参考图，保持销售顾问/主播的人物外观、气质和出镜一致性。");
        }
        if (!isSeedance2(model) && StringUtils.hasText(request.getHostImageUrl())) {
            prompt.append("当前模型使用首帧图生视频，数字人形象仅作为画面描述参考，不作为多参考图输入。");
        }
        if (StringUtils.hasText(request.getHostVideoUrl())) {
            prompt.append("画面风格适配已选择视频素材，便于后续混剪。");
        }
        prompt.append("保持车辆主体一致，广告质感，真实销售场景，避免夸张变形和无关品牌标识。");
        return trimPrompt(prompt.toString(), 1800);
    }

    private void appendPromptLine(StringBuilder prompt, String label, String value) {
        if (StringUtils.hasText(value)) {
            prompt.append(label).append("：").append(value.trim()).append("。");
        }
    }

    private String trimPrompt(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int normalizeSegmentCount(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(6, value));
    }

    private int normalizeSegmentDuration(Integer value) {
        return normalizeSegmentDuration(value, null);
    }

    private int normalizeSegmentDuration(Integer value, String model) {
        if (value == null) {
            return 8;
        }
        return Math.max(4, Math.min(maxSegmentDuration(model), value));
    }

    private int maxSegmentDuration(String model) {
        return isSeedance2(model) ? 15 : 12;
    }

    private boolean isSeedance2(String model) {
        return StringUtils.hasText(model) && SEEDANCE_2_MODEL.equals(model.trim());
    }

    private boolean shouldReferenceAudio(CarSalesVideoDTO request) {
        return request != null
                && StringUtils.hasText(request.getAudioUrl())
                && AUDIO_MODE_REFERENCE.equalsIgnoreCase(trimToDefault(request.getAudioMode(), AUDIO_MODE_POST_MIX));
    }

    private boolean hasSelectedVoiceAudio(CarSalesVideoDTO request) {
        return request != null
                && StringUtils.hasText(request.getAudioUrl())
                && !AUDIO_MODE_NONE.equalsIgnoreCase(trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE));
    }

    private boolean shouldUseFinalAudio(CarSalesVideoDTO request) {
        if (request == null || !StringUtils.hasText(request.getAudioUrl())) {
            return false;
        }
        String mode = trimToDefault(request.getAudioMode(), AUDIO_MODE_POST_MIX);
        return AUDIO_MODE_POST_MIX.equalsIgnoreCase(mode) || AUDIO_MODE_REFERENCE.equalsIgnoreCase(mode);
    }

    private String trimToDefault(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String trimmed = value.trim();
        return AUDIO_MODE_NONE.equalsIgnoreCase(trimmed) ? AUDIO_MODE_NONE : trimmed;
    }

    private Map<String, Object> buildCarSalesSeedanceDiagnostics(TaskItem task, CarSalesVideoDTO request, String model,
                                                                 int index, String finalPrompt,
                                                                 String storyboardVisualPrompt,
                                                                 Set<String> ignoredFields,
                                                                 boolean hasReferenceImage,
                                                                 boolean passesAudioUrlToSeedance) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", model);
        meta.put("taskType", task.taskType());
        meta.put("segmentIndex", index);
        meta.put("audioMode", trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE));
        meta.put("hasAudioUrl", StringUtils.hasText(request.getAudioUrl()));
        meta.put("passesAudioUrlToSeedance", passesAudioUrlToSeedance);
        meta.put("hasReferenceImage", hasReferenceImage);
        meta.put("hasBgmUrl", StringUtils.hasText(request.getBgmUrl()));
        meta.put("audioUrl", StringUtils.hasText(request.getAudioUrl()) ? request.getAudioUrl().trim() : null);
        meta.put("bgmUrl", StringUtils.hasText(request.getBgmUrl()) ? request.getBgmUrl().trim() : null);
        meta.put("storyboardVisualPrompt", storyboardVisualPrompt);
        meta.put("ignoredFields", ignoredFields == null ? List.of() : List.copyOf(ignoredFields));
        meta.put("finalPrompt", finalPrompt);
        return meta;
    }

    private String buildCarSalesSegmentInputJson(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene,
                                                 int index, Object segmentRequest, Map<String, Object> diagnostics) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "CAR_SALES_VIDEO_SEGMENT");
        meta.put("sceneIndex", index);
        meta.put("scene", scene);
        meta.put("segmentRequest", segmentRequest);
        meta.put("seedanceDiagnostics", diagnostics);
        meta.put("sourceAssetIds", request.getSourceAssetIds());
        meta.put("hostImageUrl", request.getHostImageUrl());
        return toJson(meta);
    }

    private void downloadVideoToFile(String videoUrl, Path targetFile) {
        if (!StringUtils.hasText(videoUrl)) {
            throw new BusinessException(50100, "分段视频缺少可下载地址");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(videoUrl.trim()))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50100, "Failed to download segment video, HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "Failed to download segment video: " + e.getMessage());
        }
    }

    private void stitchVideoSegments(List<Path> segmentFiles, Path outputFile) {
        if (segmentFiles == null || segmentFiles.isEmpty()) {
            throw new BusinessException(50100, "没有可拼接的视频片段");
        }
        try {
            if (segmentFiles.size() == 1) {
                Files.copy(segmentFiles.get(0), outputFile, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Path listFile = outputFile.getParent().resolve("concat-list.txt");
            List<String> lines = new ArrayList<>();
            for (Path file : segmentFiles) {
                lines.add("file '" + file.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''") + "'");
            }
            Files.write(listFile, lines, StandardCharsets.UTF_8);

            Path logFile = outputFile.getParent().resolve("ffmpeg-stitch.log");
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.toString(),
                    "-c:v", "libx264",
                    "-c:a", "aac",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg 拼接超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg 拼接失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg 拼接失败：" + e.getMessage());
        }
    }

    private Path applyCustomAudioIfPresent(String audioUrl, Path videoFile, Path tempDir, Long taskId) {
        if (!StringUtils.hasText(audioUrl)) {
            return videoFile;
        }
        Path audioFile = tempDir.resolve("custom-audio-" + taskId + guessMediaExtension(audioUrl, ".mp3"));
        Path outputFile = tempDir.resolve("car-sales-final-" + taskId + "-with-audio.mp4");
        downloadMediaToFile(audioUrl, audioFile, "音频");
        replaceVideoAudio(videoFile, audioFile, outputFile);
        return outputFile;
    }

    private Path applyBgmIfPresent(String bgmUrl, Path videoFile, Path tempDir, Long taskId, boolean hasPrimaryAudio) {
        if (!StringUtils.hasText(bgmUrl)) {
            return videoFile;
        }
        Path bgmFile = tempDir.resolve("bgm-" + taskId + guessMediaExtension(bgmUrl, ".mp3"));
        Path outputFile = tempDir.resolve("car-sales-final-" + taskId + "-with-bgm.mp4");
        downloadMediaToFile(bgmUrl, bgmFile, "BGM");
        if (hasPrimaryAudio) {
            mixBgmWithVideoAudio(videoFile, bgmFile, outputFile);
        } else {
            addBgmAsOnlyAudio(videoFile, bgmFile, outputFile);
        }
        return outputFile;
    }

    private void downloadMediaToFile(String mediaUrl, Path targetFile, String label) {
        if (!StringUtils.hasText(mediaUrl)) {
            throw new BusinessException(50100, label + "地址为空");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mediaUrl.trim()))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50100, "Failed to download " + label + ", HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "Failed to download " + label + ": " + e.getMessage());
        }
    }

    private void replaceVideoAudio(Path videoFile, Path audioFile, Path outputFile) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-audio.log");
        try {
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-i", audioFile.toString(),
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-af", "apad",
                    "-shortest",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg 音频合成超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg 音频合成失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg 音频合成失败：" + e.getMessage());
        }
    }

    private void mixBgmWithVideoAudio(Path videoFile, Path bgmFile, Path outputFile) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-bgm-mix.log");
        try {
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-stream_loop", "-1",
                    "-i", bgmFile.toString(),
                    "-filter_complex", "[1:a]volume=0.18[bgm];[0:a][bgm]amix=inputs=2:duration=first:dropout_transition=2[a]",
                    "-map", "0:v:0",
                    "-map", "[a]",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-shortest",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg BGM 混音超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg BGM 混音失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg BGM 混音失败：" + e.getMessage());
        }
    }

    private void addBgmAsOnlyAudio(Path videoFile, Path bgmFile, Path outputFile) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-bgm-only.log");
        try {
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-stream_loop", "-1",
                    "-i", bgmFile.toString(),
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "160k",
                    "-filter:a", "volume=0.35",
                    "-shortest",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg BGM 合成超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg BGM 合成失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg BGM 合成失败：" + e.getMessage());
        }
    }

    private String guessMediaExtension(String url, String fallback) {
        try {
            String path = URI.create(url.trim()).getPath();
            int dot = path == null ? -1 : path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{2,5}")) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private AssetItem saveCarSalesFinalAsset(TaskItem task, CarSalesVideoDTO request, Path finalFile, String model,
                                             String inputJson, List<VideoTaskVO> segmentVideos,
                                             List<Long> segmentAssetIds, BigDecimal totalDuration,
                                             int totalTokens) {
        if (task.ownerUserId() == null) {
            throw new BusinessException(40100, "生成视频保存到私有资产失败：缺少登录用户信息");
        }
        try (InputStream in = Files.newInputStream(finalFile)) {
            String fileName = "car-sales-video-" + task.taskId() + ".mp4";
            UploadResult stored = storageService.upload(in, Files.size(finalFile), fileName, "video/mp4", "video");
            return assetService.createGeneratedVideoAsset(
                    task.ownerUserId(),
                    task.projectId(),
                    task.taskId(),
                    stored.filename(),
                    stored.objectKey(),
                    stored.url(),
                    null,
                    stored.contentType(),
                    stored.size(),
                    task.taskType(),
                    buildCarSalesFinalMetadata(task, request, model, inputJson, segmentVideos, segmentAssetIds,
                            totalDuration, totalTokens)
            );
        } catch (Exception e) {
            throw new BusinessException(50100, "保存汽车销售总片失败：" + e.getMessage());
        }
    }

    private String buildCarSalesFinalMetadata(TaskItem task, CarSalesVideoDTO request, String model, String inputJson,
                                              List<VideoTaskVO> segmentVideos, List<Long> segmentAssetIds,
                                              BigDecimal totalDuration, int totalTokens) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "VOLCENGINE");
        meta.put("source", "CAR_SALES_VIDEO");
        meta.put("taskType", task.taskType());
        meta.put("localTaskId", task.taskId());
        meta.put("model", model);
        meta.put("durationSeconds", totalDuration);
        meta.put("completionTokens", totalTokens);
        meta.put("segmentAssetIds", segmentAssetIds);
        meta.put("segmentVideos", segmentVideos);
        meta.put("sourceAssetIds", request.getSourceAssetIds());
        meta.put("audioUrl", request.getAudioUrl());
        meta.put("audioMode", trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE));
        meta.put("bgmUrl", request.getBgmUrl());
        meta.put("ignoredStoryboardFields", request.getIgnoredStoryboardFields());
        meta.put("customAudioApplied", shouldUseFinalAudio(request));
        meta.put("audioReferenceApplied", shouldReferenceAudio(request));
        meta.put("bgmApplied", StringUtils.hasText(request.getBgmUrl()));
        meta.put("hostImageUrl", request.getHostImageUrl());
        meta.put("hostVideoUrl", request.getHostVideoUrl());
        meta.put("input", parseJsonOrRaw(inputJson));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"provider\":\"VOLCENGINE\",\"source\":\"CAR_SALES_VIDEO\"}";
        }
    }

    private void cleanupTempDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private CreateContentGenerationTaskRequest.Builder baseBuilder(String model, List<Content> contents) {
        return CreateContentGenerationTaskRequest.builder()
                .model(model)
                .content(contents);
    }

    private Content buildText(String text) {
        Content content = new Content();
        content.setType(TYPE_TEXT);
        content.setText(text);
        return content;
    }

    private Content buildImage(String url, String role) {
        ImageUrl imageUrl = new ImageUrl();
        imageUrl.setUrl(url);
        Content content = new Content();
        content.setType(TYPE_IMAGE_URL);
        content.setImageUrl(imageUrl);
        if (StringUtils.hasText(role)) {
            content.setRole(role);
        }
        return content;
    }

    private Content buildAudio(String url, String role) {
        AudioUrl audioUrl = new AudioUrl();
        audioUrl.setUrl(url);
        Content content = new Content();
        content.setType(TYPE_AUDIO_URL);
        content.setAudioUrl(audioUrl);
        if (StringUtils.hasText(role)) {
            content.setRole(role);
        }
        return content;
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /**
     * 创建任务后立即开始内部轮询，仅当任务 succeeded 且 videoUrl 非空时返回；
     * 任务 failed / cancelled / expired 或超出 pollTimeoutMillis 时抛出业务异常。
     */
    private VideoTaskVO submitAndPoll(CreateContentGenerationTaskRequest req) {
        String taskId;
        try {
            CreateContentGenerationTaskResult result = arkService.createContentGenerationTask(req);
            if (result == null || !StringUtils.hasText(result.getId())) {
                throw new BusinessException(50100, "火山方舟未返回任务 ID");
            }
            taskId = result.getId();
            log.info("Seedance 视频任务创建成功 taskId={} model={}", taskId, req.getModel());
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Seedance 视频任务创建失败 model={}", req.getModel(), e);
            throw new BusinessException(50100, "视频生成任务创建失败：" + normalizeArkErrorMessage(e.getMessage()));
        }

        long deadline = System.currentTimeMillis() + pollTimeoutMillis;
        while (true) {
            sleep(pollIntervalMillis);

            VideoTaskVO task = queryTask(taskId);
            String status = task.getStatus();
            log.debug("Seedance 任务轮询 taskId={} status={}", taskId, status);

            if (STATUS_SUCCEEDED.equalsIgnoreCase(status) && StringUtils.hasText(task.getVideoUrl())) {
                return task;
            }

            if (STATUS_FAILED.equalsIgnoreCase(status)) {
                throw new BusinessException(50300,
                        "视频生成任务失败：" + safeErrorMessage(task));
            }
            if (STATUS_CANCELLED.equalsIgnoreCase(status)) {
                throw new BusinessException(50300, "视频生成任务已取消 taskId=" + taskId);
            }
            if (STATUS_EXPIRED.equalsIgnoreCase(status)) {
                throw new BusinessException(50300, "视频生成任务已超时 taskId=" + taskId);
            }

            if (System.currentTimeMillis() > deadline) {
                throw new BusinessException(50300,
                        "视频生成轮询超时 taskId=" + taskId + " 最近状态=" + status);
            }
        }
    }

    private VideoTaskVO queryTask(String taskId) {
        try {
            GetContentGenerationTaskRequest req = GetContentGenerationTaskRequest.builder()
                    .taskId(taskId)
                    .build();
            GetContentGenerationTaskResponse resp = arkService.getContentGenerationTask(req);
            if (resp == null) {
                throw new BusinessException(40400, "任务不存在或已过期 taskId=" + taskId);
            }
            return toTaskVO(resp);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Seedance 视频任务查询失败 taskId={}", taskId, e);
            throw new BusinessException(50100, "视频任务查询失败：" + e.getMessage());
        }
    }

    private VideoTaskVO toTaskVO(GetContentGenerationTaskResponse resp) {
        VideoTaskVO.VideoTaskVOBuilder builder = VideoTaskVO.builder()
                .taskId(resp.getId())
                .model(resp.getModel())
                .status(resp.getStatus())
                .createdAt(resp.getCreatedAt())
                .updatedAt(resp.getUpdatedAt());

        GetContentGenerationTaskResponse.Content content = resp.getContent();
        if (content != null) {
            builder.videoUrl(content.getVideoUrl())
                    .lastFrameUrl(content.getLastFrameUrl());
        }

        if (resp.getUsage() != null) {
            builder.completionTokens(resp.getUsage().getCompletionTokens());
        }

        GetContentGenerationTaskResponse.ContentGenerationError error = resp.getError();
        if (error != null) {
            builder.errorCode(error.getCode())
                    .errorMessage(error.getMessage());
        }

        return builder.build();
    }

    private String safeErrorMessage(VideoTaskVO task) {
        if (StringUtils.hasText(task.getErrorMessage())) {
            return task.getErrorMessage();
        }
        if (StringUtils.hasText(task.getErrorCode())) {
            return task.getErrorCode();
        }
        return "未知原因";
    }

    private String normalizeArkErrorMessage(String rawMessage) {
        if (!StringUtils.hasText(rawMessage)) {
            return "未知原因";
        }
        String message = extractArkMessageField(rawMessage.trim());
        Matcher matcher = ARK_REQUEST_ID_SUFFIX_PATTERN.matcher(message);
        if (matcher.find()) {
            return message.substring(0, matcher.start()).trim();
        }
        return message;
    }

    private String extractArkMessageField(String rawMessage) {
        int messageIndex = rawMessage.indexOf(ARK_ERROR_MESSAGE_FIELD);
        if (messageIndex < 0) {
            return rawMessage;
        }

        String message = rawMessage.substring(messageIndex + ARK_ERROR_MESSAGE_FIELD.length()).trim();
        message = stripLeadingQuotes(message);

        int codeIndex = message.indexOf(", code=");
        if (codeIndex >= 0) {
            message = message.substring(0, codeIndex).trim();
        }

        return stripTrailingQuotes(message);
    }

    private String stripLeadingQuotes(String value) {
        int start = 0;
        while (start < value.length() && isQuoteLike(value.charAt(start))) {
            start++;
        }
        return value.substring(start);
    }

    private String stripTrailingQuotes(String value) {
        int end = value.length();
        while (end > 0) {
            char ch = value.charAt(end - 1);
            if (!isQuoteLike(ch) && ch != ',' && ch != '}') {
                break;
            }
            end--;
        }
        return value.substring(0, end).trim();
    }

    private boolean isQuoteLike(char ch) {
        return ch == '\'' || ch == '"' || ch == '’' || ch == '‘';
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50000, "视频任务轮询被中断");
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Seedance 任务结果序列化失败 reason={}", e.getMessage());
            return null;
        }
    }
}
