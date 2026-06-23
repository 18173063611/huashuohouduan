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
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesSegmentComposeRequest;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.service.VideoService;
import com.huashuo.video.subtitle.VolcengineSubtitleClient;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.AudioUrl;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.Content;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.ImageUrl;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskResult;
import com.volcengine.ark.runtime.model.content.generation.DeleteContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskResponse;
import com.volcengine.ark.runtime.service.ArkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String AUDIO_MODE_AUTO_TTS = "auto_tts";
    private static final String AUDIO_MODE_MODEL_NATIVE = "model_native";
    private static final String DEFAULT_NATIVE_VOICE_STYLE = "female_natural_explain";
    private static final String SUBTITLE_MODE_NONE = "无";
    private static final String SUBTITLE_MODE_AUTO = "自动生成";
    private static final String SUBTITLE_TIMING_AUTO = "auto";
    private static final String SUBTITLE_TIMING_AUDIO_RECOGNITION = "audio_recognition";
    private static final String SUBTITLE_TIMING_SCRIPT_TIMELINE = "script_timeline";
    private static final String SYNC_STRATEGY_AUTO = "auto";
    private static final String SYNC_STRATEGY_AUDIO_MASTER = "audio_master";
    private static final String SYNC_STRATEGY_VISUAL_MASTER = "visual_master";
    private static final String DEFAULT_SUBTITLE_FONT_FAMILY = "Microsoft YaHei";
    private static final int DEFAULT_SUBTITLE_FONT_SIZE = 20;
    private static final int CAR_SALES_SEEDANCE_PROMPT_MAX_CHARS = 1600;
    private static final double AUDIO_SYNC_MIN_DIFF_SECONDS = 0.25;
    private static final double AUDIO_SYNC_RETIME_MAX_RATIO_DELTA = 0.15;
    private static final List<String> STORYBOARD_IGNORED_FIELDS =
            List.of("content", "voiceText", "backgroundMusic", "narration", "script", "voiceover", "subtitle", "bgm");
    private static final List<String> CAR_MATERIAL_TARGET_ROLES = List.of(
            "car_exterior_front",
            "car_exterior_side",
            "car_exterior_rear",
            "car_exterior_45",
            "car_interior_dashboard",
            "car_interior_front_seat",
            "car_interior_back_seat",
            "car_interior_steering",
            "car_interior_trunk",
            "car_detail_sunroof",
            "car_detail_light",
            "car_detail_wheel",
            "car_detail_logo",
            "car_detail_seat_material",
            "scene_showroom",
            "scene_outdoor",
            "scene_road",
            "scene_night",
            "host_image"
    );
    private static final List<String> FALLBACK_CAR_IMAGE_ROLES = List.of(
            "car_exterior_front",
            "car_exterior_side",
            "car_exterior_rear",
            "car_interior_dashboard",
            "car_interior_front_seat",
            "car_interior_back_seat",
            "car_detail_light",
            "car_detail_wheel",
            "car_detail_sunroof",
            "scene_showroom"
    );
    private static final List<List<String>> CAR_SCENE_ROLE_PRIORITY = List.of(
            List.of("car_exterior_front", "car_exterior_side", "car_exterior_45", "car_exterior_rear"),
            List.of("car_interior_dashboard", "car_interior_front_seat", "car_interior_back_seat", "car_interior_steering"),
            List.of("car_detail_light", "car_detail_wheel", "car_detail_sunroof", "car_detail_logo", "car_detail_seat_material"),
            List.of("scene_showroom", "car_exterior_front", "host_image", "car_exterior_side"),
            List.of("scene_outdoor", "scene_road", "scene_night", "car_exterior_side", "car_exterior_45"),
            List.of("car_detail_logo", "car_detail_light", "car_exterior_rear", "scene_showroom")
    );
    private static final List<String> CAR_IDENTITY_ANCHOR_ROLES = List.of(
            "car_exterior_front",
            "car_exterior_side",
            "car_exterior_45",
            "car_exterior_rear"
    );
    private static final List<String> CAR_SCENE_REFERENCE_ROLES = List.of(
            "scene_showroom",
            "scene_outdoor",
            "scene_road",
            "scene_night"
    );
    private static final Map<String, String> CAR_ROLE_LABELS = carRoleLabels();
    private static final Map<String, String> CAR_ROLE_ALIASES = carRoleAliases();

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";
    private static final String ARK_ERROR_MESSAGE_FIELD = "message=";
    private static final String SEEDANCE_2_MODEL = "ep-20260512233524-85r4g";
    private static final int SEEDANCE_2_MAX_REFERENCE_IMAGES = 9;
    private static final int SEEDANCE_LEGACY_MAX_REFERENCE_IMAGES = 1;
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
    private final VolcengineSubtitleClient volcengineSubtitleClient;
    private final SeedanceResourceUrlValidator seedanceResourceUrlValidator;
    private final CarSalesAutoTtsService carSalesAutoTtsService;
    private final String arkBaseUrl;
    private final String arkApiKey;
    private final String arkTextModel;
    private final int carSalesMaxSegmentParallelism;
    private final String ffmpegPath;
    private final String ffprobePath;
    private final String subtitleFontFile;
    private final HttpClient httpClient;

    public VideoServiceImpl(
            ArkService seedanceArkService,
            @Value("${volcengine.seedance.model:doubao-seedance-1-5-pro}") String defaultModel,
            @Value("${volcengine.seedance.reference-model:doubao-seedance-1-0-lite-i2v-250428}") String referenceModel,
            @Value("${volcengine.seedance.poll-interval-seconds:5}") long pollIntervalSeconds,
            @Value("${volcengine.seedance.poll-timeout-seconds:2700}") long pollTimeoutSeconds,
            TaskService taskService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService,
            StorageService storageService,
            AssetService assetService,
            VolcengineSubtitleClient volcengineSubtitleClient,
            SeedanceResourceUrlValidator seedanceResourceUrlValidator,
            CarSalesAutoTtsService carSalesAutoTtsService,
            @Value("${volcengine.ark.base-url:${VOLCENGINE_ARK_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}") String arkBaseUrl,
            @Value("${volcengine.ark.api-key:${VOLCENGINE_ARK_API_KEY:}}") String arkApiKey,
            @Value("${volcengine.ark.model:${VOLCENGINE_ARK_MODEL:doubao-seed-2-0-mini-260215}}") String arkTextModel,
            @Value("${volcengine.seedance.car-sales-max-segment-parallelism:${volcengine.seedance.car-sales-segment-parallelism:12}}") int carSalesMaxSegmentParallelism,
            @Value("${video.stitch.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${video.stitch.ffprobe-path:ffprobe}") String ffprobePath,
            @Value("${video.subtitle.font-file:${VIDEO_SUBTITLE_FONT_FILE:}}") String subtitleFontFile
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
        this.volcengineSubtitleClient = volcengineSubtitleClient;
        this.seedanceResourceUrlValidator = seedanceResourceUrlValidator;
        this.carSalesAutoTtsService = carSalesAutoTtsService;
        this.arkBaseUrl = trimTrailingSlash(
                StringUtils.hasText(arkBaseUrl) ? arkBaseUrl.trim() : "https://ark.cn-beijing.volces.com/api/v3");
        this.arkApiKey = arkApiKey;
        this.arkTextModel = StringUtils.hasText(arkTextModel) ? arkTextModel.trim() : "doubao-seed-2-0-mini-260215";
        this.carSalesMaxSegmentParallelism = Math.max(1, Math.min(12, carSalesMaxSegmentParallelism));
        this.ffmpegPath = StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
        this.ffprobePath = StringUtils.hasText(ffprobePath) ? ffprobePath.trim() : "ffprobe";
        this.subtitleFontFile = StringUtils.hasText(subtitleFontFile) ? subtitleFontFile.trim() : null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static Map<String, String> carRoleLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("car_exterior_front", "正面");
        labels.put("car_exterior_side", "侧面");
        labels.put("car_exterior_rear", "背面");
        labels.put("car_exterior_45", "45 度角");
        labels.put("car_interior_dashboard", "中控台");
        labels.put("car_interior_front_seat", "前排");
        labels.put("car_interior_back_seat", "后排");
        labels.put("car_interior_steering", "方向盘");
        labels.put("car_interior_trunk", "后备箱");
        labels.put("car_detail_sunroof", "天窗");
        labels.put("car_detail_light", "车灯");
        labels.put("car_detail_wheel", "轮毂");
        labels.put("car_detail_logo", "Logo");
        labels.put("car_detail_seat_material", "座椅材质");
        labels.put("scene_showroom", "展厅");
        labels.put("scene_outdoor", "户外城市");
        labels.put("scene_road", "公路/山路");
        labels.put("scene_night", "夜景/门店");
        labels.put("host_image", "销售顾问/数字人");
        return Map.copyOf(labels);
    }

    private static Map<String, String> carRoleAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("front", "car_exterior_front");
        aliases.put("exterior_front", "car_exterior_front");
        aliases.put("car_front", "car_exterior_front");
        aliases.put("side", "car_exterior_side");
        aliases.put("exterior_side", "car_exterior_side");
        aliases.put("rear", "car_exterior_rear");
        aliases.put("back", "car_exterior_rear");
        aliases.put("exterior_rear", "car_exterior_rear");
        aliases.put("45", "car_exterior_45");
        aliases.put("45_degree", "car_exterior_45");
        aliases.put("car_exterior_45_degree", "car_exterior_45");
        aliases.put("dashboard", "car_interior_dashboard");
        aliases.put("interior", "car_interior_dashboard");
        aliases.put("interior_dashboard", "car_interior_dashboard");
        aliases.put("front_seat", "car_interior_front_seat");
        aliases.put("back_seat", "car_interior_back_seat");
        aliases.put("rear_seat", "car_interior_back_seat");
        aliases.put("steering", "car_interior_steering");
        aliases.put("steering_wheel", "car_interior_steering");
        aliases.put("instrument", "car_interior_dashboard");
        aliases.put("trunk", "car_interior_trunk");
        aliases.put("boot", "car_interior_trunk");
        aliases.put("sunroof", "car_detail_sunroof");
        aliases.put("panoramic_roof", "car_detail_sunroof");
        aliases.put("light", "car_detail_light");
        aliases.put("headlight", "car_detail_light");
        aliases.put("wheel", "car_detail_wheel");
        aliases.put("seat", "car_detail_seat_material");
        aliases.put("seat_material", "car_detail_seat_material");
        aliases.put("showroom", "scene_showroom");
        aliases.put("dealership", "scene_showroom");
        aliases.put("scene", "scene_showroom");
        aliases.put("outdoor", "scene_outdoor");
        aliases.put("city", "scene_outdoor");
        aliases.put("road", "scene_road");
        aliases.put("mountain", "scene_road");
        aliases.put("highway", "scene_road");
        aliases.put("night", "scene_night");
        aliases.put("store_night", "scene_night");
        aliases.put("host", "host_image");
        aliases.put("avatar", "host_image");
        return Map.copyOf(aliases);
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

    @Override
    public VideoTaskVO adoptCarSalesRegeneratedSegment(long sourceTaskId, int segmentIndex, long regeneratedTaskId,
                                                       Long viewerUserId) {
        if (segmentIndex < 1) {
            throw new BusinessException(40000, "segmentIndex must start from 1");
        }
        OptionalLong viewer = optionalUser(viewerUserId);
        taskService.getTaskForViewer(sourceTaskId, viewer);
        taskService.getTaskForViewer(regeneratedTaskId, viewer);
        TaskItem sourceTask = taskService.getTask(sourceTaskId);
        TaskItem regeneratedTask = taskService.getTask(regeneratedTaskId);
        if (!TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(sourceTask.taskType())
                || !TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(regeneratedTask.taskType())) {
            throw new BusinessException(40000, "Only car sales video tasks support segment adoption");
        }
        if (!TaskStatusCode.SUCCESS.equals(sourceTask.status())
                || !TaskStatusCode.SUCCESS.equals(regeneratedTask.status())) {
            throw new BusinessException(40900, "Original and regenerated segment tasks must both succeed first");
        }
        if (sourceTask.taskId().equals(regeneratedTask.taskId())) {
            throw new BusinessException(40000, "Regenerated task must be different from original task");
        }
        if (!StringUtils.hasText(sourceTask.inputJson()) || !StringUtils.hasText(sourceTask.outputJson())
                || !StringUtils.hasText(regeneratedTask.outputJson())) {
            throw new BusinessException(40000, "Task result is incomplete");
        }

        CarSalesVideoDTO originalRequest = readJson(sourceTask.inputJson(), CarSalesVideoDTO.class,
                "Original car sales input is invalid");
        VideoTaskVO sourceOutput = readJson(sourceTask.outputJson(), VideoTaskVO.class,
                "Original car sales result is invalid");
        restoreEffectiveCarSalesAudio(originalRequest, sourceOutput);
        VideoTaskVO regeneratedOutput = readJson(regeneratedTask.outputJson(), VideoTaskVO.class,
                "Regenerated segment result is invalid");
        List<VideoTaskVO> segments = sourceOutput.getSegmentVideos() == null
                ? new ArrayList<>()
                : new ArrayList<>(sourceOutput.getSegmentVideos());
        if (segments.size() < segmentIndex) {
            throw new BusinessException(40000, "Original task does not contain the requested segment result");
        }
        VideoTaskVO replacement = pickReplacementSegment(regeneratedOutput);
        if (!StringUtils.hasText(replacement.getVideoUrl())) {
            throw new BusinessException(40000, "Regenerated segment has no videoUrl");
        }
        segments.set(segmentIndex - 1, replacement);

        List<Long> segmentAssetIds = new ArrayList<>();
        if (sourceOutput.getSegmentAssetIds() != null) {
            segmentAssetIds.addAll(sourceOutput.getSegmentAssetIds());
        }
        while (segmentAssetIds.size() < segments.size()) {
            segmentAssetIds.add(null);
        }
        segmentAssetIds.set(segmentIndex - 1, replacement.getResultAssetId());

        String model = pickModel(originalRequest.getModel(), sourceTask.modelCode(),
                StringUtils.hasText(sourceOutput.getModel()) ? sourceOutput.getModel() : referenceModel);
        return rebuildCarSalesSegments(sourceTask, originalRequest, sourceOutput, model, segments,
                segmentAssetIds, viewer, "Failed to adopt regenerated segment");
    }

    @Override
    public VideoTaskVO composeCarSalesSegments(long sourceTaskId, CarSalesSegmentComposeRequest request,
                                               Long viewerUserId) {
        OptionalLong viewer = optionalUser(viewerUserId);
        taskService.getTaskForViewer(sourceTaskId, viewer);
        TaskItem sourceTask = taskService.getTask(sourceTaskId);
        if (!TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(sourceTask.taskType())) {
            throw new BusinessException(40000, "Only car sales video tasks support segment composition");
        }
        if (!TaskStatusCode.SUCCESS.equals(sourceTask.status())) {
            throw new BusinessException(40900, "Car sales task must succeed before segment composition");
        }
        if (!StringUtils.hasText(sourceTask.inputJson()) || !StringUtils.hasText(sourceTask.outputJson())) {
            throw new BusinessException(40000, "Task result is incomplete");
        }

        CarSalesVideoDTO originalRequest = readJson(sourceTask.inputJson(), CarSalesVideoDTO.class,
                "Original car sales input is invalid");
        VideoTaskVO sourceOutput = readJson(sourceTask.outputJson(), VideoTaskVO.class,
                "Original car sales result is invalid");
        restoreEffectiveCarSalesAudio(originalRequest, sourceOutput);
        List<VideoTaskVO> segments = resolveManualComposeSegments(request, viewer);
        List<Long> segmentAssetIds = segments.stream()
                .map(VideoTaskVO::getResultAssetId)
                .toList();
        String model = pickModel(originalRequest.getModel(), sourceTask.modelCode(),
                StringUtils.hasText(sourceOutput.getModel()) ? sourceOutput.getModel() : referenceModel);
        return rebuildCarSalesSegments(sourceTask, originalRequest, sourceOutput, model, segments,
                segmentAssetIds, viewer, "Failed to compose car sales segments");
    }

    private VideoTaskVO rebuildCarSalesSegments(TaskItem sourceTask, CarSalesVideoDTO originalRequest,
                                                VideoTaskVO sourceOutput, String model,
                                                List<VideoTaskVO> segments, List<Long> segmentAssetIds,
                                                OptionalLong viewer, String errorPrefix) {
        BigDecimal totalDuration = sumSegmentDurations(segments, originalRequest, model);
        int totalTokens = sumCompletionTokens(segments);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("car-sales-repatch-" + sourceTask.taskId() + "-");
            List<Path> segmentFiles = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                VideoTaskVO segment = segments.get(i);
                Path segmentFile = tempDir.resolve("segment-" + (i + 1) + ".mp4");
                downloadVideoToFile(segment.getVideoUrl(), segmentFile);
                segmentFiles.add(segmentFile);
            }
            Path stitchedFile = tempDir.resolve("car-sales-restitch-" + sourceTask.taskId() + ".mp4");
            stitchVideoSegments(segmentFiles, stitchedFile);
            boolean useFinalVoiceAudio = shouldUseFinalAudio(originalRequest);
            boolean hasPrimaryAudio = useFinalVoiceAudio
                    || shouldGenerateNativeAudio(originalRequest)
                    || shouldReferenceAudio(originalRequest);
            MediaProcessResult voiceProcess = applyCustomAudioIfPresent(useFinalVoiceAudio ? originalRequest.getAudioUrl() : null,
                    stitchedFile, tempDir, sourceTask.taskId(), originalRequest);
            totalDuration = mediaDurationOrFallback(voiceProcess.durationSeconds(), totalDuration);
            Path finalVideoFile = applyBgmIfPresent(originalRequest.getBgmUrl(), voiceProcess.videoFile(), tempDir,
                    sourceTask.taskId(), hasPrimaryAudio);
            totalDuration = mediaDurationOrFallback(probeMediaDurationSeconds(finalVideoFile), totalDuration);
            AssetItem finalAsset = saveCarSalesFinalAsset(sourceTask, originalRequest, finalVideoFile, model,
                    sourceTask.inputJson(), segments, segmentAssetIds, totalDuration, totalTokens);
            long now = System.currentTimeMillis() / 1000L;
            sourceOutput.setTaskId(StringUtils.hasText(sourceOutput.getTaskId())
                    ? sourceOutput.getTaskId()
                    : "car-sales-" + sourceTask.taskId());
            sourceOutput.setLocalTaskId(sourceTask.taskId());
            sourceOutput.setModel(model);
            sourceOutput.setStatus(STATUS_SUCCEEDED);
            if (sourceOutput.getCreatedAt() == null) {
                sourceOutput.setCreatedAt(now);
            }
            sourceOutput.setUpdatedAt(now);
            sourceOutput.setVideoUrl(finalAsset.fileUrl());
            sourceOutput.setResultAssetId(finalAsset.assetId());
            sourceOutput.setFinalAssetId(finalAsset.assetId());
            sourceOutput.setSegmentVideos(segments);
            sourceOutput.setSegmentAssetIds(segmentAssetIds);
            sourceOutput.setSegmentCount(segments.size());
            sourceOutput.setDurationSeconds(totalDuration);
            sourceOutput.setTotalDurationSeconds(totalDuration);
            sourceOutput.setCompletionTokens(totalTokens);
            taskService.replaceSuccessfulTaskResult(sourceTask.taskId(), toJson(sourceOutput), viewer);
            return sourceOutput;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, errorPrefix + ": " + e.getMessage());
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private List<VideoTaskVO> resolveManualComposeSegments(CarSalesSegmentComposeRequest request,
                                                           OptionalLong viewer) {
        if (request == null || request.getSegments() == null || request.getSegments().isEmpty()) {
            throw new BusinessException(40000, "segments is required");
        }
        List<VideoTaskVO> segments = new ArrayList<>();
        int index = 1;
        for (CarSalesSegmentComposeRequest.Segment item : request.getSegments()) {
            if (item == null) {
                throw new BusinessException(40000, "segment item is required");
            }
            VideoTaskVO segment = new VideoTaskVO();
            String videoUrl = trimToNull(item.getVideoUrl());
            if (item.getAssetId() != null) {
                AssetItem asset = assetService.getAssetForViewer(item.getAssetId(), viewer);
                if (asset == null || !"VIDEO".equalsIgnoreCase(trimToDefault(asset.assetType(), ""))) {
                    throw new BusinessException(40000, "Only video assets can be used for segment composition");
                }
                if (!StringUtils.hasText(videoUrl)) {
                    videoUrl = trimToNull(asset.fileUrl());
                }
                segment.setResultAssetId(asset.assetId());
                segment.setTaskId("asset-" + asset.assetId());
            } else {
                segment.setTaskId("manual-" + index);
            }
            if (!StringUtils.hasText(videoUrl)) {
                throw new BusinessException(40000, "segment videoUrl is required");
            }
            segment.setVideoUrl(videoUrl);
            segment.setStatus(STATUS_SUCCEEDED);
            segments.add(segment);
            index++;
        }
        return segments;
    }

    private OptionalLong optionalUser(Long userId) {
        return userId == null ? OptionalLong.empty() : OptionalLong.of(userId);
    }

    private <T> T readJson(String json, Class<T> type, String errorMessage) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BusinessException(40000, errorMessage);
        }
    }

    private void restoreEffectiveCarSalesAudio(CarSalesVideoDTO request, VideoTaskVO sourceOutput) {
        if (request == null || sourceOutput == null) {
            return;
        }
        AssetItem finalAsset = loadFinalVideoAsset(sourceOutput);
        if (finalAsset == null || !StringUtils.hasText(finalAsset.metadataJson())) {
            return;
        }
        try {
            JsonNode meta = objectMapper.readTree(finalAsset.metadataJson());
            String generatedVoiceUrl = jsonText(meta, "generatedVoiceUrl");
            String audioUrl = firstNonBlank(jsonText(meta, "audioUrl"), generatedVoiceUrl);
            String audioMode = firstNonBlank(jsonText(meta, "audioMode"), request.getAudioMode());
            String voicePolicy = firstNonBlank(jsonText(meta, "voicePolicy"), request.getVoicePolicy());
            String bgmUrl = jsonText(meta, "bgmUrl");

            if (!StringUtils.hasText(request.getGeneratedVoiceUrl()) && StringUtils.hasText(generatedVoiceUrl)) {
                request.setGeneratedVoiceUrl(generatedVoiceUrl);
            }
            Long generatedVoiceAssetId = jsonLong(meta, "generatedVoiceAssetId");
            if (request.getGeneratedVoiceAssetId() == null && generatedVoiceAssetId != null) {
                request.setGeneratedVoiceAssetId(generatedVoiceAssetId);
            }
            if (!StringUtils.hasText(request.getAudioUrl())
                    && StringUtils.hasText(audioUrl)
                    && !AUDIO_MODE_NONE.equalsIgnoreCase(trimToDefault(audioMode, AUDIO_MODE_POST_MIX))) {
                request.setAudioUrl(audioUrl);
            }
            if (StringUtils.hasText(request.getAudioUrl())) {
                if ("auto_tts".equalsIgnoreCase(voicePolicy)
                        || AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(trimToDefault(audioMode, AUDIO_MODE_NONE))
                        || StringUtils.hasText(generatedVoiceUrl)) {
                    request.setAudioMode(AUDIO_MODE_POST_MIX);
                } else {
                    request.setAudioMode(trimToDefault(audioMode, AUDIO_MODE_POST_MIX));
                }
            } else if (!StringUtils.hasText(request.getAudioMode()) && StringUtils.hasText(audioMode)) {
                request.setAudioMode(audioMode);
            }
            if (!StringUtils.hasText(request.getVoicePolicy()) && StringUtils.hasText(voicePolicy)) {
                request.setVoicePolicy(voicePolicy);
            }
            if (!StringUtils.hasText(request.getBgmUrl()) && StringUtils.hasText(bgmUrl)) {
                request.setBgmUrl(bgmUrl);
            }
        } catch (Exception e) {
            log.warn("Restore car sales audio metadata failed finalAssetId={} reason={}",
                    finalAsset.assetId(), e.getMessage());
        }
    }

    private AssetItem loadFinalVideoAsset(VideoTaskVO sourceOutput) {
        List<Long> candidateIds = new ArrayList<>();
        if (sourceOutput.getFinalAssetId() != null) {
            candidateIds.add(sourceOutput.getFinalAssetId());
        }
        if (sourceOutput.getResultAssetId() != null) {
            candidateIds.add(sourceOutput.getResultAssetId());
        }
        for (Long assetId : candidateIds) {
            if (assetId == null || assetId <= 0) {
                continue;
            }
            try {
                AssetItem asset = assetService.getAsset(assetId);
                if (asset != null) {
                    return asset;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String jsonText(JsonNode node, String field) {
        if (node == null || !StringUtils.hasText(field)) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isTextual()) {
            return trimToNull(child.asText());
        }
        if (child.isNumber() || child.isBoolean()) {
            return trimToNull(child.asText());
        }
        return null;
    }

    private Long jsonLong(JsonNode node, String field) {
        if (node == null || !StringUtils.hasText(field)) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isIntegralNumber()) {
            return child.longValue();
        }
        if (child.isTextual()) {
            try {
                return Long.parseLong(child.asText().trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private VideoTaskVO pickReplacementSegment(VideoTaskVO regeneratedOutput) {
        if (regeneratedOutput.getSegmentVideos() != null && !regeneratedOutput.getSegmentVideos().isEmpty()) {
            return regeneratedOutput.getSegmentVideos().get(0);
        }
        return regeneratedOutput;
    }

    private BigDecimal sumSegmentDurations(List<VideoTaskVO> segments, CarSalesVideoDTO request, String model) {
        BigDecimal total = BigDecimal.ZERO;
        List<CarSalesVideoDTO.Scene> scenes = request == null ? null : request.getScenes();
        for (int i = 0; i < segments.size(); i++) {
            VideoTaskVO segment = segments.get(i);
            BigDecimal duration = segment == null ? null : segment.getDurationSeconds();
            if (duration == null || duration.signum() <= 0) {
                Integer sceneDuration = scenes != null && i < scenes.size() && scenes.get(i) != null
                        ? scenes.get(i).getDuration()
                        : request == null ? null : request.getSegmentDuration();
                duration = BigDecimal.valueOf(normalizeSegmentDuration(sceneDuration, model));
            }
            total = total.add(duration);
        }
        return total;
    }

    private int sumCompletionTokens(List<VideoTaskVO> segments) {
        int total = 0;
        for (VideoTaskVO segment : segments) {
            if (segment != null && segment.getCompletionTokens() != null) {
                total += segment.getCompletionTokens();
            }
        }
        return total;
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

        String firstFrameUrl = resolveGeneratedFirstFrameUrl(arkResult, inputJson);
        String thumbnailUrl = firstNonBlank(firstFrameUrl, arkResult.getLastFrameUrl());
        return assetService.createGeneratedVideoAsset(
                task.ownerUserId(),
                task.projectId(),
                task.taskId(),
                stored == null ? fileName : stored.filename(),
                stored == null ? "external-url:" + task.taskId() : stored.objectKey(),
                stored == null ? originalVideoUrl : stored.url(),
                thumbnailUrl,
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
        String firstFrameUrl = resolveGeneratedFirstFrameUrl(arkResult, inputJson);
        meta.put("firstFrameUrl", firstFrameUrl);
        meta.put("lastFrameUrl", arkResult.getLastFrameUrl());
        meta.put("coverUrl", firstNonBlank(firstFrameUrl, arkResult.getLastFrameUrl()));
        meta.put("thumbnailUrl", firstNonBlank(firstFrameUrl, arkResult.getLastFrameUrl()));
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

    private String resolveGeneratedFirstFrameUrl(VideoTaskVO result, String inputJson) {
        String fromResult = result == null ? null : result.getFirstFrameUrl();
        String fromInput = firstFrameUrlFromInputJson(inputJson);
        return firstNonBlank(fromResult, fromInput);
    }

    private String firstFrameUrlFromInputJson(String inputJson) {
        if (!StringUtils.hasText(inputJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(inputJson);
            return firstNonBlank(
                    jsonTextAt(root, "/firstFrameUrl"),
                    jsonTextAt(root, "/imageUrl"),
                    jsonTextAt(root, "/referenceImage"),
                    jsonTextAt(root, "/segmentRequest/firstFrameUrl"),
                    jsonTextAt(root, "/segmentRequest/imageUrl"),
                    jsonTextAt(root, "/segmentRequest/imageUrls/0"),
                    jsonTextAt(root, "/imageUrls/0"),
                    jsonTextAt(root, "/scene/referenceImage"),
                    jsonTextAt(root, "/scene/imageUrls/0")
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private String jsonTextAt(JsonNode root, String pointer) {
        if (root == null || !StringUtils.hasText(pointer)) {
            return null;
        }
        JsonNode node = root.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return trimToNull(node.asText());
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
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
                .ratio(normalizeSeedanceRatio(request.getRatio()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req);
    }

    private VideoTaskVO doGenerateFirstFrame(ImageDTO request, String model) {
        return doGenerateFirstFrame(request, model, null);
    }

    private VideoTaskVO doGenerateFirstFrame(ImageDTO request, String model, PollObserver pollObserver) {
        List<Content> contents = new ArrayList<>();
        if (StringUtils.hasText(request.getPrompt())) {
            contents.add(buildText(request.getPrompt()));
        }
        contents.add(buildImage(request.getImageUrl(), ROLE_FIRST_FRAME));
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .ratio(normalizeSeedanceRatio(request.getRatio()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req, pollObserver);
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
                .ratio(normalizeSeedanceRatio(request.getRatio()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req);
    }

    private VideoTaskVO doGenerateReference(ImageReferenceDTO request, String model) {
        return doGenerateReference(request, model, null);
    }

    private VideoTaskVO doGenerateReference(ImageReferenceDTO request, String model, PollObserver pollObserver) {
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
                .ratio(normalizeSeedanceRatio(request.getRatio()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(request.getGenerateAudio() == null || request.getGenerateAudio())
                .build();
        return submitAndPoll(req, pollObserver);
    }

    private VideoTaskVO doGenerateCarSalesVideo(TaskItem task, CarSalesVideoDTO request, String model, String inputJson) {
        normalizeCarSalesVoicePolicy(request);
        SanitizedStoryboard sanitizedContext = sanitizeStoryboardText(request.getScriptContext(),
                hostAppearanceEnabled(request), isStrictVoiceText(request));
        ensureNoStoryboardPollution(sanitizedContext.text());
        request.setScriptContext(sanitizedContext.text());
        List<CarSalesVideoDTO.Scene> scenes = resolveCarSalesScenes(request, model);
        enforceStrictVoiceConsistency(request, scenes);
        prepareModelNativeVoiceover(request, scenes);
        prepareAutoTtsVoiceover(task, request, scenes, model);
        boolean referenceAudio = shouldReferenceAudio(request);
        if (referenceAudio && !isSeedance2(model)) {
            throw new BusinessException(40000, "参考音频生成仅支持 seedance2.0");
        }
        if (referenceAudio && scenes.size() != 1) {
            throw new BusinessException(40000, "参考音频生成当前仅支持 1 段视频，多段成片请使用后期口播配音，BGM 请单独选择");
        }
        List<VideoTaskVO> segmentVideos = Collections.synchronizedList(new ArrayList<>());
        List<Long> segmentAssetIds = Collections.synchronizedList(new ArrayList<>());
        List<Path> segmentFiles = new ArrayList<>();
        BigDecimal totalDuration = BigDecimal.ZERO;
        int totalTokens = 0;
        boolean useSeedance2Reference = isSeedance2(model);
        boolean useFinalVoiceAudio = shouldUseFinalAudio(request);
        boolean hasBgm = StringUtils.hasText(request.getBgmUrl());
        boolean generateNativeAudio = referenceAudio || shouldGenerateNativeAudio(request);

        Path tempDir = null;
        ExecutorService segmentExecutor = null;
        try {
            tempDir = Files.createTempDirectory("car-sales-video-" + task.taskId() + "-");
            publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                    0, scenes.size(), 14, "正在整理车辆素材与成片结构");
            if (shouldGenerateNativeAudio(request)) {
                publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                        0, scenes.size(), 18, "已整理口播文案，准备由视频模型生成匹配音视频");
            } else if (AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(trimToDefault(request.getVoicePolicy(), AUDIO_MODE_NONE))) {
                publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                        0, scenes.size(), 18, "口播音频已生成，准备开始分段画面");
            }
            int segmentParallelism = resolveCarSalesSegmentParallelism(scenes.size());
            AtomicInteger completedSegments = new AtomicInteger(0);
            AtomicInteger threadCounter = new AtomicInteger(1);
            Path segmentTempDir = tempDir;
            segmentExecutor = Executors.newFixedThreadPool(segmentParallelism, runnable -> {
                Thread thread = new Thread(runnable,
                        "car-sales-segment-" + task.taskId() + "-" + threadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            });
            Map<String, Object> startExtra = new LinkedHashMap<>();
            startExtra.put("renderStrategy", "parallel_segments");
            startExtra.put("segmentParallelism", segmentParallelism);
            publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                    0, scenes.size(), 22,
                    "开始并行生成 " + scenes.size() + " 段视频，并发数 " + segmentParallelism, startExtra);

            int totalSceneSegments = scenes.size();
            List<CompletableFuture<CarSalesSegmentResult>> futures = new ArrayList<>();
            for (int i = 0; i < totalSceneSegments; i++) {
                int segmentIndex = i + 1;
                CarSalesVideoDTO.Scene scene = scenes.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> generateCarSalesSegment(
                        task, request, model, sanitizedContext, scene, segmentIndex, totalSceneSegments,
                        useSeedance2Reference, referenceAudio, generateNativeAudio, segmentTempDir,
                        segmentVideos, segmentAssetIds, completedSegments, segmentParallelism), segmentExecutor));
            }

            List<CarSalesSegmentResult> results = new ArrayList<>();
            try {
                for (CompletableFuture<CarSalesSegmentResult> future : futures) {
                    results.add(future.join());
                }
            } catch (CompletionException e) {
                futures.forEach(future -> future.cancel(true));
                throw unwrapCompletionException(e);
            }

            results.sort(Comparator.comparingInt(CarSalesSegmentResult::index));
            segmentVideos.clear();
            segmentAssetIds.clear();
            for (CarSalesSegmentResult result : results) {
                segmentVideos.add(result.segment());
                if (result.assetId() != null) {
                    segmentAssetIds.add(result.assetId());
                }
                segmentFiles.add(result.segmentFile());
                if (result.duration() != null) {
                    totalDuration = totalDuration.add(result.duration());
                }
                totalTokens += result.completionTokens();
            }

            Path finalFile = tempDir.resolve("car-sales-final-" + task.taskId() + ".mp4");
            publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                    segmentVideos.size(), scenes.size(), 86, "分段视频已完成，正在合成为整条视频");
            stitchVideoSegments(segmentFiles, finalFile);
            publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                    segmentVideos.size(), scenes.size(), 90, "正在处理最终口播与背景音乐");
            MediaProcessResult voiceProcess = applyCustomAudioIfPresent(useFinalVoiceAudio ? request.getAudioUrl() : null,
                    finalFile, tempDir, task.taskId(), request);
            totalDuration = mediaDurationOrFallback(voiceProcess.durationSeconds(), totalDuration);
            Path finalVideoFile = applyBgmIfPresent(request.getBgmUrl(), voiceProcess.videoFile(), tempDir, task.taskId(),
                    useFinalVoiceAudio || generateNativeAudio);
            totalDuration = mediaDurationOrFallback(probeMediaDurationSeconds(finalVideoFile), totalDuration);
            publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                    segmentVideos.size(), scenes.size(), 95, "正在处理字幕与视频大字报");
            Path subtitledVideoFile = burnSubtitlesIfNeeded(request, finalVideoFile, voiceProcess.videoFile(),
                    tempDir, task.taskId(), totalDuration, scenes);
            Path outputVideoFile = applyHeadlineOverlayIfNeeded(request, subtitledVideoFile, tempDir, task.taskId());
            AssetItem finalAsset = saveCarSalesFinalAsset(task, request, outputVideoFile, model, inputJson,
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
                    .firstFrameUrl(finalAsset.thumbnailUrl())
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
            if (segmentExecutor != null) {
                segmentExecutor.shutdownNow();
            }
            cleanupTempDir(tempDir);
        }
    }

    private CarSalesSegmentResult generateCarSalesSegment(
            TaskItem task,
            CarSalesVideoDTO request,
            String model,
            SanitizedStoryboard sanitizedContext,
            CarSalesVideoDTO.Scene scene,
            int segmentIndex,
            int totalSegments,
            boolean useSeedance2Reference,
            boolean referenceAudio,
            boolean generateNativeAudio,
            Path tempDir,
            List<VideoTaskVO> completedSegmentVideos,
            List<Long> completedSegmentAssetIds,
            AtomicInteger completedSegments,
            int segmentParallelism) {
        Map<String, Object> startExtra = new LinkedHashMap<>();
        startExtra.put("renderStrategy", "parallel_segments");
        startExtra.put("segmentParallelism", segmentParallelism);
        startExtra.put("activeSegmentIndex", segmentIndex);
        publishCarSalesPartialProgress(task, request, model, completedSegmentVideos, completedSegmentAssetIds,
                completedSegments.get(), totalSegments,
                carSalesSegmentCompleteProgress(completedSegments.get(), totalSegments),
                "第 " + segmentIndex + " / " + totalSegments + " 段已提交并行生成", startExtra);

        SceneImageSelection imageSelection = resolveSceneImageSelection(request, scene, segmentIndex, model);
        boolean hasSceneReference = hasSceneReference(imageSelection);
        List<String> sceneImages = imageSelection.imageUrls();
        SanitizedStoryboard sanitizedScene = sanitizeStoryboardText(resolveSceneVisualPrompt(scene),
                hostAppearanceEnabled(request), isStrictVoiceText(request));
        String sceneVisualPrompt = sanitizedScene.text();
        Set<String> sceneIgnoredFields = new LinkedHashSet<>(sanitizedScene.ignoredFields());
        if (hasSceneReference) {
            String sceneReferenceSafePrompt = sanitizeScenePromptForSceneReference(
                    sceneVisualPrompt, hostAppearanceEnabled(request));
            if (StringUtils.hasText(sceneVisualPrompt)
                    && StringUtils.hasText(sceneReferenceSafePrompt)
                    && !sceneVisualPrompt.equals(sceneReferenceSafePrompt)) {
                sceneIgnoredFields.add("sceneReferenceEnvironment");
            }
            sceneVisualPrompt = sceneReferenceSafePrompt;
        }
        ensureNoStoryboardPollution(sceneVisualPrompt);
        applySanitizedScenePrompt(scene, sceneVisualPrompt);

        Set<String> ignoredFields = new LinkedHashSet<>(sanitizedContext.ignoredFields());
        if (request.getIgnoredStoryboardFields() != null) {
            ignoredFields.addAll(request.getIgnoredStoryboardFields());
        }
        ignoredFields.addAll(sceneIgnoredFields);
        if (hasSelectedVoiceAudio(request) && scene != null && StringUtils.hasText(scene.getVoiceText())) {
            ignoredFields.add("voiceText");
        }

        String scenePrompt = buildCarSalesScenePrompt(request, scene, segmentIndex, totalSegments, model, imageSelection);
        int segmentDuration = normalizeSegmentDuration(scene == null ? null : scene.getDuration(), model);
        Map<String, Object> diagnostics = buildCarSalesSeedanceDiagnostics(task, request, model, segmentIndex,
                scenePrompt, sanitizedContext.text(), ignoredFields, imageSelection,
                useSeedance2Reference, referenceAudio);
        log.info("Seedance car sales generation params taskId={} segment={} diagnostics={}",
                task.taskId(), segmentIndex, toJson(diagnostics));

        Object segmentRequest;
        VideoTaskVO segment;
        PollObserver pollObserver = carSalesParallelSegmentPollObserver(
                task, request, model, completedSegmentVideos, completedSegmentAssetIds,
                completedSegments, segmentIndex, totalSegments, segmentParallelism);
        String segmentFirstFrameUrl;
        if (useSeedance2Reference) {
            ImageReferenceDTO referenceRequest = new ImageReferenceDTO();
            referenceRequest.setImageUrls(sceneImages);
            referenceRequest.setPrompt(scenePrompt);
            referenceRequest.setDuration(segmentDuration);
            referenceRequest.setRatio(normalizeSeedanceRatio(request.getAspectRatio()));
            referenceRequest.setModel(model);
            referenceRequest.setGenerateAudio(generateNativeAudio);
            if (referenceAudio) {
                referenceRequest.setAudioUrls(List.of(request.getAudioUrl().trim()));
            }
            segmentRequest = referenceRequest;
            segment = doGenerateReference(referenceRequest, model, pollObserver);
            segmentFirstFrameUrl = firstNonBlank(sceneImages.get(0));
        } else {
            ImageDTO firstFrameRequest = new ImageDTO();
            firstFrameRequest.setImageUrl(sceneImages.get(0));
            firstFrameRequest.setPrompt(scenePrompt);
            firstFrameRequest.setDuration(segmentDuration);
            firstFrameRequest.setRatio(normalizeSeedanceRatio(request.getAspectRatio()));
            firstFrameRequest.setModel(model);
            firstFrameRequest.setGenerateAudio(generateNativeAudio);
            segmentRequest = firstFrameRequest;
            segment = doGenerateFirstFrame(firstFrameRequest, model, pollObserver);
            segmentFirstFrameUrl = firstFrameRequest.getImageUrl();
        }

        segment.setLocalTaskId(task.taskId());
        segment.setFirstFrameUrl(segmentFirstFrameUrl);
        BigDecimal duration = resolveDurationSeconds(segment, segmentDuration);
        segment.setDurationSeconds(duration);

        Path segmentFile = tempDir.resolve("segment-" + segmentIndex + ".mp4");
        downloadVideoToFile(segment.getVideoUrl(), segmentFile);

        String segmentInputJson = buildCarSalesSegmentInputJson(request, scene, segmentIndex, segmentRequest, diagnostics);
        AssetItem segmentAsset = saveSeedanceVideoAsset(task, segment, model, segmentInputJson);
        Long assetId = null;
        if (segmentAsset != null) {
            segment.setVideoUrl(segmentAsset.fileUrl());
            segment.setResultAssetId(segmentAsset.assetId());
            assetId = segmentAsset.assetId();
        }

        synchronized (completedSegmentVideos) {
            completedSegmentVideos.add(segment);
        }
        if (assetId != null) {
            synchronized (completedSegmentAssetIds) {
                completedSegmentAssetIds.add(assetId);
            }
        }
        int completed = completedSegments.incrementAndGet();
        Map<String, Object> completeExtra = new LinkedHashMap<>();
        completeExtra.put("renderStrategy", "parallel_segments");
        completeExtra.put("segmentParallelism", segmentParallelism);
        completeExtra.put("activeSegmentIndex", segmentIndex);
        publishCarSalesPartialProgress(task, request, model, completedSegmentVideos, completedSegmentAssetIds,
                completed, totalSegments, carSalesSegmentCompleteProgress(completed, totalSegments),
                "已完成第 " + segmentIndex + " / " + totalSegments + " 段，整体完成 "
                        + completed + " / " + totalSegments + " 段", completeExtra);

        int completionTokens = segment.getCompletionTokens() == null ? 0 : segment.getCompletionTokens();
        return new CarSalesSegmentResult(segmentIndex, segment, assetId, segmentFile, duration, completionTokens);
    }

    private int resolveCarSalesSegmentParallelism(int segmentCount) {
        return Math.max(1, Math.min(Math.max(1, segmentCount), carSalesMaxSegmentParallelism));
    }

    private RuntimeException unwrapCompletionException(CompletionException exception) {
        Throwable cause = exception == null ? null : exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new BusinessException(50100,
                "汽车销售成片并行分段生成失败：" + (cause == null ? "未知异常" : cause.getMessage()));
    }

    private record CarSalesSegmentResult(
            int index,
            VideoTaskVO segment,
            Long assetId,
            Path segmentFile,
            BigDecimal duration,
            int completionTokens
    ) {
    }

    private record MediaProcessResult(
            Path videoFile,
            Double durationSeconds,
            Double sourceVideoDurationSeconds,
            Double primaryAudioDurationSeconds,
            String syncStrategy
    ) {
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
                if (scenes.size() >= 12) {
                    break;
                }
            }
            if (!scenes.isEmpty()) {
                return scenes;
            }
        }

        if (isMultiCarCompareRequest(request)) {
            List<CarSalesVideoDTO.Scene> compareScenes = buildDefaultMultiCarCompareScenes(request, model);
            if (!compareScenes.isEmpty()) {
                return compareScenes;
            }
        }

        String[] titles = {
                "外观开场", "车头灯光", "内饰座舱", "座椅空间",
                "核心卖点", "用车场景", "细节质感", "门店试驾",
                "安全智能", "尾部收束", "生活氛围", "优惠收口"
        };
        String[] prompts = {
                "整车外观作为开场建立，车头和车身线条清晰，镜头慢速推进，形成第一眼吸引力。",
                "围绕车头、灯组、前脸和车身高光做近景展示，镜头小幅横移，突出辨识度和质感。",
                "展示中控屏、方向盘、仪表、座舱氛围和材质，镜头从前排空间平稳扫过。",
                "展示座椅、后排腿部空间、储物和乘坐舒适性，镜头从座椅延伸到空间纵深。",
                "围绕动力、智能、安全、油耗/续航或配置亮点做节奏感展示，画面干净有销售说服力。",
                "展示城市通勤、家庭出行或周末短途场景，让车辆与真实生活需求结合。",
                "用车灯、轮毂、天窗、Logo、座椅材质或车漆反光做特写，镜头稳定停留在一个细节重点。",
                "用门店、交付、试驾邀约和咨询动作形成转化氛围，画面适合短视频汽车销售。",
                "展示辅助驾驶、屏幕交互、安全配置或舒适配置的视觉化表达，镜头干净、有科技感。",
                "展示车尾、尾灯、后备箱或车身侧后方，作为视觉收束并承接下一段。",
                "展示车辆与真实生活场景的关系，画面温和、可信，让目标客户能代入使用。",
                "再次展示整车高光和优惠咨询氛围，镜头稳定收口，强化立即咨询和预约试驾。"
        };
        List<CarSalesVideoDTO.Scene> scenes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int templateIndex = i % titles.length;
            CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
            scene.setSegmentIndex(i + 1);
            scene.setTitle(titles[templateIndex]);
            scene.setVisualPrompt(prompts[templateIndex]);
            scene.setPrompt(prompts[templateIndex]);
            scene.setDuration(normalizeSegmentDuration(request.getSegmentDuration(), model));
            scenes.add(scene);
        }
        return scenes;
    }

    private List<CarSalesVideoDTO.Scene> buildDefaultMultiCarCompareScenes(CarSalesVideoDTO request, String model) {
        if (request == null || request.getCarPackages() == null || request.getCarPackages().size() < 2) {
            return List.of();
        }
        List<CarSalesVideoDTO.Scene> scenes = new ArrayList<>();
        int duration = normalizeSegmentDuration(request.getSegmentDuration(), model);
        CarSalesVideoDTO.Scene opening = new CarSalesVideoDTO.Scene();
        opening.setSegmentIndex(1);
        opening.setTitle("对比开场");
        opening.setVisualPrompt("多车型对比开场，建立车型并列关系，清楚展示不同车型身份。");
        opening.setPrompt(opening.getVisualPrompt());
        opening.setImageUrls(compareAnchorImageUrls(request));
        opening.setDuration(duration);
        opening.setCompareDimension("opening");
        opening.setShotPurpose("opening");
        scenes.add(opening);

        for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
            if (carPackage == null || scenes.size() >= 10) {
                continue;
            }
            String name = StringUtils.hasText(carPackage.getBrandModel())
                    ? carPackage.getBrandModel().trim()
                    : trimToDefault(carPackage.getPackageName(), "车型" + scenes.size());
            CarSalesVideoDTO.Scene intro = new CarSalesVideoDTO.Scene();
            intro.setSegmentIndex(scenes.size() + 1);
            intro.setTitle(name + "单车介绍");
            intro.setVisualPrompt(name + "单车介绍章节，只展示该车型素材包内的外观、内饰、空间或核心卖点，不出现其他车型。");
            intro.setPrompt(intro.getVisualPrompt());
            intro.setImageUrls(packageImageReferenceUrls(carPackage));
            intro.setDuration(duration);
            intro.setCarPackageId(carPackage.getPackageId());
            intro.setCarIndex(carPackage.getCarIndex());
            intro.setCarRole(carPackage.getRole());
            intro.setCompareDimension("single_car_intro");
            intro.setShotPurpose("single_car_intro");
            scenes.add(intro);
        }

        CarSalesVideoDTO.Scene compare = new CarSalesVideoDTO.Scene();
        compare.setSegmentIndex(scenes.size() + 1);
        compare.setTitle("维度对比");
        compare.setVisualPrompt("并列对比多款车型的外观、空间、配置和推荐人群，每款车只使用自己的参考图和事实。");
        compare.setPrompt(compare.getVisualPrompt());
        compare.setImageUrls(compareAnchorImageUrls(request));
        compare.setDuration(duration);
        compare.setCompareDimension("外观 / 空间 / 配置 / 推荐");
        compare.setShotPurpose("dimension_compare");
        scenes.add(compare);

        CarSalesVideoDTO.Scene summary = new CarSalesVideoDTO.Scene();
        summary.setSegmentIndex(scenes.size() + 1);
        summary.setTitle("总结推荐");
        summary.setVisualPrompt("总结推荐段落，按不同需求给出选择建议，画面以双车锚点或主推车型高光收束。");
        summary.setPrompt(summary.getVisualPrompt());
        summary.setImageUrls(compareAnchorImageUrls(request));
        summary.setDuration(duration);
        summary.setCompareDimension("summary");
        summary.setShotPurpose("summary_recommendation");
        scenes.add(summary);
        return scenes;
    }

    private int carSalesSegmentStartProgress(int segmentIndex, int totalSegments) {
        int total = Math.max(1, totalSegments);
        int index = Math.max(1, Math.min(segmentIndex, total));
        return 22 + Math.round(((index - 1) * 58f) / total);
    }

    private int carSalesSegmentCompleteProgress(int completedSegments, int totalSegments) {
        int total = Math.max(1, totalSegments);
        int completed = Math.max(0, Math.min(completedSegments, total));
        return 22 + Math.round((completed * 58f) / total);
    }

    private void publishCarSalesPartialProgress(TaskItem task, CarSalesVideoDTO request, String model,
                                                List<VideoTaskVO> segmentVideos, List<Long> segmentAssetIds,
                                                int completedSegments, int totalSegments, int progress,
                                                String stage) {
        publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                completedSegments, totalSegments, progress, stage, null);
    }

    private void publishCarSalesPartialProgress(TaskItem task, CarSalesVideoDTO request, String model,
                                                List<VideoTaskVO> segmentVideos, List<Long> segmentAssetIds,
                                                int completedSegments, int totalSegments, int progress,
                                                String stage, Map<String, Object> extra) {
        if (task == null || task.taskId() == null) {
            return;
        }
        int clampedProgress = Math.max(0, Math.min(99, progress));
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("taskId", "car-sales-" + task.taskId());
        partial.put("localTaskId", task.taskId());
        partial.put("model", model);
        partial.put("status", "running");
        partial.put("partial", true);
        partial.put("stage", stage);
        partial.put("progress", clampedProgress);
        partial.put("completedSegmentCount", Math.max(0, completedSegments));
        partial.put("segmentCount", Math.max(1, totalSegments));
        partial.put("segmentVideos", snapshotList(segmentVideos));
        partial.put("segmentAssetIds", snapshotList(segmentAssetIds));
        partial.put("voicePolicy", request == null ? null : request.getVoicePolicy());
        partial.put("taskMode", request == null ? null : request.getTaskMode());
        partial.put("carPackages", request == null ? null : request.getCarPackages());
        partial.put("finalVoiceText", request == null ? null : request.getFinalVoiceText());
        partial.put("nativeVoiceLanguage", request == null ? null : request.getNativeVoiceLanguage());
        partial.put("nativeVoiceStyle", request == null ? null : request.getNativeVoiceStyle());
        partial.put("nativeSpeechStyle", request == null ? null : request.getNativeSpeechStyle());
        partial.put("generatedVoiceAssetId", request == null ? null : request.getGeneratedVoiceAssetId());
        partial.put("generatedVoiceUrl", request == null ? null : request.getGeneratedVoiceUrl());
        if (extra != null && !extra.isEmpty()) {
            partial.putAll(extra);
        }
        String json = toJson(partial);
        if (StringUtils.hasText(json)) {
            taskService.updateTaskProgress(task.taskId(), clampedProgress, json);
        } else {
            taskService.updateTaskProgress(task.taskId(), clampedProgress);
        }
    }

    private <T> List<T> snapshotList(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        synchronized (source) {
            return List.copyOf(source);
        }
    }

    private PollObserver carSalesParallelSegmentPollObserver(TaskItem task, CarSalesVideoDTO request, String model,
                                                             List<VideoTaskVO> segmentVideos,
                                                             List<Long> segmentAssetIds,
                                                             AtomicInteger completedSegments,
                                                             int segmentIndex, int totalSegments,
                                                             int segmentParallelism) {
        long[] lastHeartbeatAt = {0L};
        return (providerTaskId, providerTask, elapsedMillis, timeoutMillis) -> {
            long now = System.currentTimeMillis();
            if (lastHeartbeatAt[0] > 0 && now - lastHeartbeatAt[0] < 30_000L) {
                return;
            }
            lastHeartbeatAt[0] = now;
            try {
                int completed = completedSegments == null ? 0 : completedSegments.get();
                int baseProgress = carSalesSegmentCompleteProgress(completed, totalSegments);
                int nextProgress = carSalesSegmentCompleteProgress(Math.min(completed + 1, totalSegments), totalSegments);
                int progressRoom = Math.max(0, nextProgress - baseProgress - 1);
                int heartbeatProgress = baseProgress + Math.min(progressRoom,
                        (int) Math.max(0, TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) / 2));
                String providerStatus = providerTask == null ? null : providerTask.getStatus();
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("renderStrategy", "parallel_segments");
                extra.put("segmentParallelism", segmentParallelism);
                extra.put("activeSegmentIndex", segmentIndex);
                extra.put("activeProviderTaskId", providerTaskId);
                extra.put("activeProviderStatus", providerStatus);
                extra.put("activeSegmentElapsedSeconds", TimeUnit.MILLISECONDS.toSeconds(Math.max(0, elapsedMillis)));
                extra.put("activeSegmentTimeoutSeconds", TimeUnit.MILLISECONDS.toSeconds(Math.max(0, timeoutMillis)));
                extra.put("activeProviderUpdatedAt", providerTask == null ? null : providerTask.getUpdatedAt());
                publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                        completed, totalSegments, heartbeatProgress,
                        "并行生成中：第 " + segmentIndex + " / " + Math.max(1, totalSegments)
                                + " 段已等待 " + formatElapsedForStage(elapsedMillis)
                                + (StringUtils.hasText(providerStatus)
                                ? "，模型状态：" + seedanceStatusLabel(providerStatus) : ""),
                        extra);
            } catch (Exception e) {
                log.warn("Seedance car sales parallel heartbeat ignored taskId={} segment={} reason={}",
                        task == null ? null : task.taskId(), segmentIndex, e.getMessage());
            }
        };
    }

    private PollObserver carSalesSegmentPollObserver(TaskItem task, CarSalesVideoDTO request, String model,
                                                     List<VideoTaskVO> segmentVideos, List<Long> segmentAssetIds,
                                                     int segmentIndex, int totalSegments) {
        long[] lastHeartbeatAt = {0L};
        return (providerTaskId, providerTask, elapsedMillis, timeoutMillis) -> {
            long now = System.currentTimeMillis();
            if (lastHeartbeatAt[0] > 0 && now - lastHeartbeatAt[0] < 30_000L) {
                return;
            }
            lastHeartbeatAt[0] = now;
            try {
                int startProgress = carSalesSegmentStartProgress(segmentIndex, totalSegments);
                int maxProgressBeforeComplete = Math.max(startProgress,
                        carSalesSegmentCompleteProgress(segmentIndex, totalSegments) - 1);
                int progressRoom = Math.max(0, maxProgressBeforeComplete - startProgress);
                int heartbeatProgress = startProgress + Math.min(progressRoom,
                        (int) Math.max(0, TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) / 2));
                String providerStatus = providerTask == null ? null : providerTask.getStatus();
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("activeSegmentIndex", segmentIndex);
                extra.put("activeProviderTaskId", providerTaskId);
                extra.put("activeProviderStatus", providerStatus);
                extra.put("activeSegmentElapsedSeconds", TimeUnit.MILLISECONDS.toSeconds(Math.max(0, elapsedMillis)));
                extra.put("activeSegmentTimeoutSeconds", TimeUnit.MILLISECONDS.toSeconds(Math.max(0, timeoutMillis)));
                extra.put("activeProviderUpdatedAt", providerTask == null ? null : providerTask.getUpdatedAt());
                publishCarSalesPartialProgress(task, request, model, segmentVideos, segmentAssetIds,
                        segmentIndex - 1, totalSegments, heartbeatProgress,
                        carSalesSegmentPollingStage(segmentIndex, totalSegments, elapsedMillis, providerStatus), extra);
            } catch (Exception e) {
                log.warn("Seedance car sales heartbeat ignored taskId={} segment={} reason={}",
                        task == null ? null : task.taskId(), segmentIndex, e.getMessage());
            }
        };
    }

    private String carSalesSegmentPollingStage(int segmentIndex, int totalSegments,
                                               long elapsedMillis, String providerStatus) {
        String stage = "正在生成第 " + segmentIndex + " / " + Math.max(1, totalSegments)
                + " 段，已等待 " + formatElapsedForStage(elapsedMillis);
        if (StringUtils.hasText(providerStatus)) {
            stage += "，模型状态：" + seedanceStatusLabel(providerStatus);
        }
        return stage;
    }

    private String formatElapsedForStage(long elapsedMillis) {
        long seconds = Math.max(0, TimeUnit.MILLISECONDS.toSeconds(elapsedMillis));
        long minutes = seconds / 60;
        if (minutes > 0) {
            return minutes + " 分钟";
        }
        return Math.max(1, seconds) + " 秒";
    }

    private String seedanceStatusLabel(String status) {
        if (!StringUtils.hasText(status)) {
            return "未知";
        }
        return switch (status.trim().toLowerCase()) {
            case STATUS_QUEUED -> "排队中";
            case STATUS_RUNNING -> "生成中";
            case STATUS_SUCCEEDED -> "已完成";
            case STATUS_FAILED -> "失败";
            case STATUS_CANCELLED -> "已取消";
            case STATUS_EXPIRED -> "已过期";
            default -> status.trim();
        };
    }

    private SceneImageSelection resolveSceneImageSelection(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene,
                                                           int sceneIndex, String model) {
        List<String> sourceUrls = cleanUrls(scene == null ? null : scene.getImageUrls());
        if (sourceUrls.isEmpty()) {
            sourceUrls = sceneScopedImageReferenceUrls(request, scene);
        }
        if (sourceUrls.isEmpty()) {
            sourceUrls = allImageReferenceUrls(request);
        }
        List<CarImageCandidate> candidates = resolveCarImageCandidates(request, sourceUrls);
        if (candidates.isEmpty()) {
            throw new BusinessException(40000, "每个 scene 至少需要 1 张车辆图片");
        }

        List<String> priorities = carSceneRolePriority(scene, sceneIndex);
        if (!hostAppearanceEnabled(request)) {
            priorities = priorities.stream()
                    .filter(role -> !"host_image".equals(role))
                    .toList();
        }
        List<CarImageCandidate> selected = new ArrayList<>();
        List<String> priorityRoles = priorities;
        if (isSeedance2(model)) {
            priorityRoles = seedance2ReferencePriorityRoles(request, priorities);
            List<String> sceneRolesInSource = candidates.stream()
                    .map(CarImageCandidate::role)
                    .filter(CAR_SCENE_REFERENCE_ROLES::contains)
                    .distinct()
                    .toList();
            addFirstCandidateByAnyRole(selected, candidates,
                    sceneRolesInSource.isEmpty() ? CAR_SCENE_REFERENCE_ROLES : sceneRolesInSource);
            if (hostAppearanceEnabled(request)) {
                addFirstCandidateByRole(selected, candidates, "host_image");
            }
            addFirstCandidateByAnyRole(selected, candidates, CAR_IDENTITY_ANCHOR_ROLES);
        }
        addCandidatesByRoles(selected, candidates, priorityRoles);
        if (isSeedance2(model)) {
            addCandidatesByRoles(selected, candidates, CAR_IDENTITY_ANCHOR_ROLES);
            if (selected.stream().noneMatch(candidate -> isVehicleReferenceRole(candidate.role()))) {
                addFirstCandidateByAnyRole(selected,
                        resolveCarImageCandidates(request, allImageReferenceUrls(request)),
                        CAR_IDENTITY_ANCHOR_ROLES);
            }
        } else {
            for (CarImageCandidate candidate : candidates) {
                if (!containsUrl(selected, candidate.url())) {
                    selected.add(candidate);
                }
            }
        }

        int maxRefs = isSeedance2(model) ? SEEDANCE_2_MAX_REFERENCE_IMAGES : SEEDANCE_LEGACY_MAX_REFERENCE_IMAGES;
        if (selected.size() > maxRefs) {
            selected = new ArrayList<>(selected.subList(0, maxRefs));
        }
        if (selected.isEmpty()) {
            selected.add(candidates.get(0));
        }

        List<String> imageUrls = selected.stream().map(CarImageCandidate::url).toList();
        List<String> roles = selected.stream().map(CarImageCandidate::role).toList();
        List<String> labels = selected.stream().map(CarImageCandidate::label).toList();
        return new SceneImageSelection(
                imageUrls,
                roles,
                labels,
                isSeedance2(model) ? "seedance2_role_matched_multi_reference" : "seedance15_role_matched_first_frame",
                priorityRoles
        );
    }

    private List<String> sceneScopedImageReferenceUrls(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene) {
        if (!isMultiCarCompareRequest(request)) {
            return List.of();
        }
        if (isCompareScene(scene)) {
            return compareAnchorImageUrls(request);
        }
        CarSalesVideoDTO.CarPackage carPackage = findSceneCarPackage(request, scene);
        if (carPackage == null) {
            return List.of();
        }
        return packageImageReferenceUrls(carPackage);
    }

    private List<String> compareAnchorImageUrls(CarSalesVideoDTO request) {
        if (request == null || request.getCarPackages() == null) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
            String anchor = firstPackageAnchorImageUrl(carPackage);
            if (StringUtils.hasText(anchor) && !urls.contains(anchor)) {
                urls.add(anchor);
            }
        }
        return urls;
    }

    private String firstPackageAnchorImageUrl(CarSalesVideoDTO.CarPackage carPackage) {
        List<String> packageUrls = packageImageReferenceUrls(carPackage);
        if (packageUrls.isEmpty()) {
            return null;
        }
        Map<String, CarSalesVideoDTO.AssetRoleBinding> bindings = new LinkedHashMap<>();
        if (carPackage != null && carPackage.getAssetRoleBindings() != null) {
            for (CarSalesVideoDTO.AssetRoleBinding binding : carPackage.getAssetRoleBindings()) {
                if (binding != null && StringUtils.hasText(binding.getUrl())) {
                    bindings.put(binding.getUrl().trim(), binding);
                }
            }
        }
        for (String role : CAR_IDENTITY_ANCHOR_ROLES) {
            for (String url : packageUrls) {
                CarSalesVideoDTO.AssetRoleBinding binding = bindings.get(url);
                if (role.equals(normalizeCarAssetRole(binding == null ? null : binding.getAssetRole()))) {
                    return url;
                }
            }
        }
        return packageUrls.get(0);
    }

    private boolean isCompareScene(CarSalesVideoDTO.Scene scene) {
        if (scene == null) {
            return false;
        }
        String purpose = trimToNull(scene.getShotPurpose());
        return containsAny((purpose == null ? "" : purpose.toLowerCase())
                        + " "
                        + String.valueOf(scene.getCompareDimension()).toLowerCase(),
                "compare", "对比", "summary", "总结", "opening", "开场");
    }

    private CarSalesVideoDTO.CarPackage findSceneCarPackage(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene) {
        if (request == null || scene == null || request.getCarPackages() == null) {
            return null;
        }
        String packageId = trimToNull(scene.getCarPackageId());
        Integer carIndex = scene.getCarIndex();
        for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
            if (carPackage == null) {
                continue;
            }
            if (StringUtils.hasText(packageId) && packageId.equals(trimToNull(carPackage.getPackageId()))) {
                return carPackage;
            }
            if (carIndex != null && carIndex.equals(carPackage.getCarIndex())) {
                return carPackage;
            }
        }
        return null;
    }

    private boolean isVehicleReferenceRole(String role) {
        return StringUtils.hasText(role) && role.startsWith("car_");
    }

    private List<CarImageCandidate> resolveCarImageCandidates(CarSalesVideoDTO request, List<String> sourceUrls) {
        Map<String, CarSalesVideoDTO.AssetRoleBinding> bindingByUrl = imageBindingsByUrl(request);
        List<String> allCarUrls = cleanUrls(request == null ? null : request.getCarImageUrls());
        List<CarImageCandidate> candidates = new ArrayList<>();
        int order = 0;
        for (String url : sourceUrls) {
            CarSalesVideoDTO.AssetRoleBinding binding = bindingByUrl.get(url);
            String role = normalizeCarAssetRole(binding == null ? null : binding.getAssetRole());
            if (!StringUtils.hasText(role) && binding == null) {
                role = fallbackRoleForUrl(url, allCarUrls);
            }
            if (!hostAppearanceEnabled(request) && "host_image".equals(role)) {
                continue;
            }
            candidates.add(new CarImageCandidate(
                    url,
                    role,
                    roleLabel(role),
                    binding == null ? null : binding.getAssetId(),
                    order++
            ));
        }

        String hostImageUrl = hostAppearanceEnabled(request)
                ? trimToNull(request == null ? null : request.getHostImageUrl())
                : null;
        if (StringUtils.hasText(hostImageUrl) && candidates.stream().noneMatch(item -> hostImageUrl.equals(item.url()))) {
            CarSalesVideoDTO.AssetRoleBinding binding = bindingByUrl.get(hostImageUrl);
            candidates.add(new CarImageCandidate(
                    hostImageUrl,
                    "host_image",
                    roleLabel("host_image"),
                    binding == null ? null : binding.getAssetId(),
                    order
            ));
        }

        List<CarImageCandidate> deduped = new ArrayList<>();
        for (CarImageCandidate candidate : candidates) {
            if (!containsUrl(deduped, candidate.url())) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }

    private Map<String, CarSalesVideoDTO.AssetRoleBinding> imageBindingsByUrl(CarSalesVideoDTO request) {
        Map<String, CarSalesVideoDTO.AssetRoleBinding> bindings = new LinkedHashMap<>();
        if (request == null || request.getAssetRoleBindings() == null) {
            addPackageImageBindings(request, bindings);
            return bindings;
        }
        for (CarSalesVideoDTO.AssetRoleBinding binding : request.getAssetRoleBindings()) {
            if (binding == null || !StringUtils.hasText(binding.getUrl())) {
                continue;
            }
            String assetType = trimToNull(binding.getAssetType());
            if (StringUtils.hasText(assetType) && !"IMAGE".equalsIgnoreCase(assetType)) {
                continue;
            }
            bindings.put(binding.getUrl().trim(), binding);
        }
        addPackageImageBindings(request, bindings);
        return bindings;
    }

    private void addPackageImageBindings(CarSalesVideoDTO request,
                                         Map<String, CarSalesVideoDTO.AssetRoleBinding> bindings) {
        if (request == null || request.getCarPackages() == null || bindings == null) {
            return;
        }
        for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
            if (carPackage == null || carPackage.getAssetRoleBindings() == null) {
                continue;
            }
            for (CarSalesVideoDTO.AssetRoleBinding binding : carPackage.getAssetRoleBindings()) {
                if (binding == null || !StringUtils.hasText(binding.getUrl())) {
                    continue;
                }
                String assetType = trimToNull(binding.getAssetType());
                if (StringUtils.hasText(assetType) && !"IMAGE".equalsIgnoreCase(assetType)) {
                    continue;
                }
                bindings.putIfAbsent(binding.getUrl().trim(), binding);
            }
        }
    }

    private List<String> allImageReferenceUrls(CarSalesVideoDTO request) {
        List<String> urls = new ArrayList<>(cleanUrls(request == null ? null : request.getCarImageUrls()));
        if (request != null && request.getCarPackages() != null) {
            for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
                for (String url : packageImageReferenceUrls(carPackage)) {
                    if (!urls.contains(url)) {
                        urls.add(url);
                    }
                }
            }
        }
        for (String url : imageBindingsByUrl(request).keySet()) {
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private List<String> packageImageReferenceUrls(CarSalesVideoDTO.CarPackage carPackage) {
        if (carPackage == null) {
            return List.of();
        }
        List<String> urls = new ArrayList<>(cleanUrls(carPackage.getImageUrls()));
        for (String url : cleanUrls(carPackage.getSceneImageUrls())) {
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        if (carPackage.getAssetRoleBindings() != null) {
            for (CarSalesVideoDTO.AssetRoleBinding binding : carPackage.getAssetRoleBindings()) {
                if (binding == null || !StringUtils.hasText(binding.getUrl())) {
                    continue;
                }
                String assetType = trimToNull(binding.getAssetType());
                if ("AUDIO".equalsIgnoreCase(assetType) || "JSON".equalsIgnoreCase(assetType)) {
                    continue;
                }
                if (!urls.contains(binding.getUrl().trim())) {
                    urls.add(binding.getUrl().trim());
                }
            }
        }
        return urls;
    }

    private List<String> cleanUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<String> clean = new ArrayList<>();
        for (String url : urls) {
            if (StringUtils.hasText(url) && !clean.contains(url.trim())) {
                clean.add(url.trim());
            }
        }
        return clean;
    }

    private String fallbackRoleForUrl(String url, List<String> carImageUrls) {
        int index = carImageUrls == null ? -1 : carImageUrls.indexOf(url);
        if (index >= 0 && index < FALLBACK_CAR_IMAGE_ROLES.size()) {
            return FALLBACK_CAR_IMAGE_ROLES.get(index);
        }
        return "car_exterior_front";
    }

    private List<String> carSceneRolePriority(CarSalesVideoDTO.Scene scene, int sceneIndex) {
        String text = ((scene == null ? "" : String.valueOf(scene.getTitle())) + " "
                + (scene == null ? "" : String.valueOf(resolveSceneVisualPrompt(scene)))).toLowerCase();
        if (containsAny(text, "天窗", "全景天窗", "全景天幕", "sunroof", "panoramic roof")) {
            return List.of("car_detail_sunroof");
        }
        if (containsAny(text, "座椅", "前排", "后排", "腿部空间", "乘坐", "seat", "front seat", "rear seat")) {
            return List.of("car_interior_front_seat", "car_interior_back_seat",
                    "car_interior_dashboard", "car_interior_steering", "car_interior_trunk");
        }
        if (containsAny(text, "方向盘", "steering", "steering wheel")) {
            return List.of("car_interior_steering", "car_interior_dashboard",
                    "car_interior_front_seat", "car_interior_back_seat");
        }
        if (containsAny(text, "中控", "仪表", "座舱", "dashboard", "instrument", "cockpit")) {
            return List.of("car_interior_dashboard", "car_interior_steering",
                    "car_interior_front_seat", "car_interior_back_seat", "car_interior_trunk");
        }
        if (containsAny(text, "后备箱", "储物", "trunk", "boot", "storage")) {
            return List.of("car_interior_trunk", "car_interior_back_seat",
                    "car_interior_front_seat", "car_interior_dashboard");
        }
        if (containsAny(text, "车灯", "灯光", "灯组", "light", "headlight")) {
            return List.of("car_detail_light", "car_exterior_front", "car_exterior_45", "car_exterior_side");
        }
        if (containsAny(text, "轮毂", "轮圈", "wheel", "rim")) {
            return List.of("car_detail_wheel", "car_exterior_side", "car_exterior_45", "car_exterior_front");
        }
        if (containsAny(text, "logo", "标识", "车标", "brand mark")) {
            return List.of("car_detail_logo", "car_exterior_front", "car_exterior_45");
        }
        if (containsAny(text, "座椅材质", "材质", "皮质", "seat material", "upholstery")) {
            return List.of("car_detail_seat_material", "car_interior_front_seat",
                    "car_interior_back_seat", "car_interior_dashboard");
        }
        if (containsAny(text, "内饰", "座椅", "中控", "空间", "前排", "后排", "方向盘", "仪表", "后备箱",
                "interior", "seat", "dashboard", "trunk")) {
            return List.of("car_interior_dashboard", "car_interior_front_seat", "car_interior_back_seat",
                    "car_interior_steering", "car_interior_trunk");
        }
        if (containsAny(text, "车灯", "灯光", "轮毂", "logo", "标识", "细节", "材质",
                "light", "wheel", "detail", "logo")) {
            return List.of("car_detail_light", "car_exterior_front", "car_exterior_45",
                    "car_detail_wheel", "car_detail_sunroof", "car_detail_logo", "car_detail_seat_material");
        }
        if (containsAny(text, "展厅", "门店", "到店", "试驾", "邀约", "销售顾问",
                "showroom", "store", "dealer")) {
            return List.of("scene_showroom", "car_exterior_front", "host_image", "car_exterior_side");
        }
        if (containsAny(text, "户外", "城市", "公路", "道路", "山路", "夜景", "通勤", "出行",
                "outdoor", "city", "road", "night")) {
            return List.of("scene_outdoor", "scene_road", "scene_night", "car_exterior_side", "car_exterior_45");
        }
        if (containsAny(text, "外观", "车头", "车身", "正面", "侧面", "背面",
                "exterior", "front", "side", "rear")) {
            return CAR_SCENE_ROLE_PRIORITY.get(0);
        }
        int idx = Math.max(0, Math.min(CAR_SCENE_ROLE_PRIORITY.size() - 1, sceneIndex - 1));
        return CAR_SCENE_ROLE_PRIORITY.get(idx);
    }

    private List<String> seedance2ReferencePriorityRoles(CarSalesVideoDTO request, List<String> scenePriorities) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (hostAppearanceEnabled(request)) {
            roles.add("host_image");
        }
        if (scenePriorities != null) {
            scenePriorities.stream()
                    .filter(CAR_SCENE_REFERENCE_ROLES::contains)
                    .forEach(roles::add);
        }
        roles.addAll(CAR_SCENE_REFERENCE_ROLES);
        roles.addAll(CAR_IDENTITY_ANCHOR_ROLES);
        if (scenePriorities != null) {
            roles.addAll(scenePriorities);
        }
        return List.copyOf(roles);
    }

    private void addCandidatesByRoles(List<CarImageCandidate> selected, List<CarImageCandidate> candidates,
                                      List<String> roles) {
        if (roles == null) {
            return;
        }
        for (String role : roles) {
            addFirstCandidateByRole(selected, candidates, role);
        }
    }

    private boolean addFirstCandidateByAnyRole(List<CarImageCandidate> selected, List<CarImageCandidate> candidates,
                                               List<String> roles) {
        if (roles == null) {
            return false;
        }
        for (String role : roles) {
            if (addFirstCandidateByRole(selected, candidates, role)) {
                return true;
            }
        }
        return false;
    }

    private boolean addFirstCandidateByRole(List<CarImageCandidate> selected, List<CarImageCandidate> candidates,
                                            String role) {
        if (!StringUtils.hasText(role)) {
            return false;
        }
        for (CarImageCandidate candidate : candidates) {
            if (role.equals(candidate.role()) && !containsUrl(selected, candidate.url())) {
                selected.add(candidate);
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUrl(List<CarImageCandidate> candidates, String url) {
        return candidates.stream().anyMatch(item -> item.url().equals(url));
    }

    private String normalizeCarAssetRole(String role) {
        if (!StringUtils.hasText(role)) {
            return "";
        }
        String normalized = role.trim().toLowerCase().replaceAll("[\\s-]+", "_");
        String aliased = CAR_ROLE_ALIASES.getOrDefault(normalized, normalized);
        return CAR_MATERIAL_TARGET_ROLES.contains(aliased) ? aliased : "";
    }

    private String roleLabel(String role) {
        return StringUtils.hasText(role) ? CAR_ROLE_LABELS.getOrDefault(role, role) : "未标记";
    }

    private Map<String, Object> buildCarMaterialCompleteness(CarSalesVideoDTO request) {
        Set<String> providedRoles = new LinkedHashSet<>();
        for (CarImageCandidate candidate : resolveCarImageCandidates(request, allImageReferenceUrls(request))) {
            if (StringUtils.hasText(candidate.role())) {
                providedRoles.add(candidate.role());
            }
        }
        List<String> missingRoles = CAR_MATERIAL_TARGET_ROLES.stream()
                .filter(role -> !providedRoles.contains(role))
                .toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("providedCount", providedRoles.size());
        meta.put("totalCount", CAR_MATERIAL_TARGET_ROLES.size());
        meta.put("percent", CAR_MATERIAL_TARGET_ROLES.isEmpty()
                ? 0
                : Math.round((providedRoles.size() * 100.0f) / CAR_MATERIAL_TARGET_ROLES.size()));
        meta.put("providedRoles", List.copyOf(providedRoles));
        meta.put("providedLabels", providedRoles.stream().map(this::roleLabel).toList());
        meta.put("missingRoles", missingRoles);
        meta.put("missingLabels", missingRoles.stream().map(this::roleLabel).toList());
        return meta;
    }

    private record CarImageCandidate(String url, String role, String label, Long assetId, int order) {
    }

    private record SceneImageSelection(
            List<String> imageUrls,
            List<String> roles,
            List<String> labels,
            String strategy,
            List<String> priorityRoles
    ) {
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

    private record CarSalesShotPlan(
            String intent,
            String shotSize,
            String cameraMotion,
            String composition,
            String subjectAction,
            String pacing,
            String transition
    ) {
    }

    private SanitizedStoryboard sanitizeStoryboardText(String raw, boolean hostEnabled) {
        return sanitizeStoryboardText(raw, hostEnabled, false);
    }

    private SanitizedStoryboard sanitizeStoryboardText(String raw, boolean hostEnabled, boolean strictVoiceText) {
        if (!StringUtils.hasText(raw)) {
            return new SanitizedStoryboard(null, List.of());
        }
        Set<String> ignoredFields = new LinkedHashSet<>();
        if (strictVoiceText) {
            raw = stripStoryboardVoiceReferences(raw, ignoredFields);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            String visualText = extractStoryboardVisualText(root, ignoredFields, hostEnabled);
            if (StringUtils.hasText(visualText)) {
                return new SanitizedStoryboard(trimPrompt(visualText, 3000), List.copyOf(ignoredFields));
            }
        } catch (Exception ignored) {
            // 非 JSON 文本也只提取可复用的镜头执行信息，避免旧车型、旧人物、旧场景污染生成。
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
        String execution = storyboardExecutionText(sanitized, 1, 1, hostEnabled);
        return new SanitizedStoryboard(trimPrompt(execution, 3000), List.copyOf(ignoredFields));
    }

    private String stripStoryboardVoiceReferences(String raw, Set<String> ignoredFields) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String cleaned = raw;
        Pattern oldVoiceReference = Pattern.compile(
                "(?im)[；;]?\\s*(原分镜台词参考|原分镜台词|旧台词|台词参考|本段口播台词|口播台词)\\s*[:：]?\\s*[^\\n\\r]*");
        Matcher matcher = oldVoiceReference.matcher(cleaned);
        if (matcher.find()) {
            ignoredFields.add("storyboardVoiceReference");
            cleaned = matcher.replaceAll("");
        }
        Pattern audioSubtitleInstruction = Pattern.compile(
                "(?im)^.*(内容主导|口播|配音|音频|原生音频|字幕|字幕烧录|字幕文字|BGM|voice|narration|speech|subtitle|caption|audio).*(\\R|$)");
        matcher = audioSubtitleInstruction.matcher(cleaned);
        if (matcher.find()) {
            ignoredFields.add("audioSubtitleInstruction");
            cleaned = matcher.replaceAll("");
        }
        return cleaned;
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

    private String extractStoryboardVisualText(JsonNode root, Set<String> ignoredFields, boolean hostEnabled) {
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode scenesNode = root.isArray() ? root : firstArray(root, "scripts", "storyboard", "shots", "scenes", "segments");
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
            String time = firstText(node, "time", "duration", "durationSec", "estDurationSec", "range");
            String visual = firstText(node, "page", "visualPrompt", "visual", "scene", "shot", "picture", "prompt",
                    "description");
            String highlight = firstText(node, "highlight", "intent", "goal");
            String camera = firstText(node, "camera", "cameraMotion", "movement", "motion", "shotType",
                    "framing", "composition", "transition");
            if (!StringUtils.hasText(visual) && !StringUtils.hasText(highlight)
                    && !StringUtils.hasText(camera)) {
                index++;
                continue;
            }
            String rawVisual = String.join(" ",
                    visual == null ? "" : visual,
                    highlight == null ? "" : highlight,
                    camera == null ? "" : camera);
            List<String> parts = new ArrayList<>();
            parts.add("镜头" + (StringUtils.hasText(order) ? order : index));
            if (StringUtils.hasText(time)) {
                parts.add("时间 " + time.trim());
            }
            CarSalesShotPlan shotPlan = buildCarSalesShotPlan(null, rawVisual,
                    index, Math.max(1, scenesNode.size()), false, hostEnabled);
            parts.add("镜头意图 " + shotPlan.intent());
            parts.add("导演执行 " + shotPlanSummary(shotPlan));
            String peoplePolicy = storyboardPeoplePolicyText(rawVisual, hostEnabled);
            if (StringUtils.hasText(peoplePolicy)) {
                parts.add(peoplePolicy);
            }
            lines.add(String.join("；", parts));
            index++;
        }
        return String.join("\n", lines);
    }

    private String storyboardIntentText(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase();
        LinkedHashSet<String> intents = new LinkedHashSet<>();
        if (containsAny(text, "内饰", "座椅", "中控", "空间", "前排", "后排", "方向盘", "仪表", "后备箱", "天窗",
                "interior", "seat", "dashboard", "trunk", "sunroof")) {
            intents.add("展示车辆内饰空间与舒适配置");
        }
        if (containsAny(text, "车灯", "灯光", "轮毂", "天窗", "logo", "标识", "细节", "材质",
                "light", "wheel", "sunroof", "detail")) {
            intents.add("展示车辆细节特写");
        }
        if (containsAny(text, "外观", "车头", "车身", "整车", "正面", "侧面", "背面", "环绕",
                "exterior", "front", "side", "rear")) {
            intents.add("展示车辆外观与车身线条");
        }
        if (containsAny(text, "展厅", "门店", "到店", "试驾", "邀约", "联系", "咨询", "转化",
                "showroom", "store", "dealer")) {
            intents.add("保留销售引导和转化动作");
        }
        if (containsAny(text, "户外", "城市", "公路", "道路", "山路", "夜景", "通勤", "出行",
                "outdoor", "city", "road", "night")) {
            intents.add("展示用车场景和行驶氛围");
        }
        if (containsAny(text, "开场", "介绍", "打招呼", "hello", "hi")) {
            intents.add("开场介绍车辆与业务");
        }
        return intents.isEmpty()
                ? "按当前口播安排镜头转场和展示节奏"
                : String.join("，", intents);
    }

    private String storyboardExecutionText(String raw, int index, int total, boolean hostEnabled) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        CarSalesShotPlan shotPlan = buildCarSalesShotPlan(null, raw, index, total, false, hostEnabled);
        String peoplePolicy = storyboardPeoplePolicyText(raw, hostEnabled);
        return "镜头意图 " + shotPlan.intent()
                + "；导演执行 " + shotPlanSummary(shotPlan)
                + (StringUtils.hasText(peoplePolicy) ? "；" + peoplePolicy : "");
    }

    private String storyboardPeoplePolicyText(String raw, boolean hostEnabled) {
        if (!hasHumanDescription(raw)) {
            return hostEnabled
                    ? "人物处理 原分镜没有明确人物出镜，若开启数字人，仅在讲解/邀约需要时弱出镜，不要硬塞人物"
                    : "人物处理 原分镜无明确人物出镜，保持车辆和场景为主";
        }
        return hostEnabled
                ? "人物处理 保留原分镜人物站位、动作和出镜节奏；人物身份、人脸、服装以当前数字人形象/设置为准，不复刻旧人物"
                : "人物处理 忽略原分镜人物、主播、客户、路人、司机、乘客和手部，只保留镜头运动、景别、构图和车辆/场景展示节奏";
    }

    private boolean hasHumanDescription(String text) {
        return containsAny(text,
                "人物", "真人", "人脸", "人像", "半身", "全身", "主播", "销售顾问", "讲解员", "顾问",
                "客户", "顾客", "路人", "行人", "司机", "乘客", "试驾者", "出镜", "口型", "表情",
                "眼神", "服装", "手持", "站在", "走进", "挥手",
                "person", "people", "host", "presenter", "salesman", "saleswoman", "customer", "driver");
    }

    private CarSalesShotPlan buildCarSalesShotPlan(String title, String visualPrompt, int index, int total,
                                                   boolean hasSceneReference, boolean hostEnabled) {
        String text = ((title == null ? "" : title) + " "
                + (visualPrompt == null ? "" : visualPrompt)).trim().toLowerCase();
        int safeTotal = Math.max(1, total);
        int safeIndex = Math.max(1, Math.min(index, safeTotal));
        boolean interior = containsAny(text, "内饰", "座椅", "中控", "空间", "前排", "后排", "方向盘", "仪表", "后备箱", "天窗",
                "interior", "seat", "dashboard", "trunk", "sunroof");
        boolean detail = containsAny(text, "车灯", "灯光", "轮毂", "天窗", "logo", "标识", "细节", "材质", "特写",
                "light", "wheel", "sunroof", "detail", "close", "macro");
        boolean exterior = containsAny(text, "外观", "车头", "车身", "整车", "正面", "侧面", "背面", "环绕",
                "exterior", "front", "side", "rear");
        boolean conversion = containsAny(text, "展厅", "门店", "到店", "试驾", "邀约", "联系", "咨询", "转化", "优惠",
                "showroom", "store", "dealer", "cta");
        boolean lifestyle = containsAny(text, "户外", "城市", "公路", "道路", "山路", "夜景", "通勤", "出行", "家庭",
                "outdoor", "city", "road", "night", "drive");
        boolean opening = safeIndex == 1 || containsAny(text, "开场", "介绍", "打招呼", "hello", "hi");
        boolean closing = safeIndex == safeTotal || containsAny(text, "收口", "结尾", "关注", "预约", "下单");

        String intent = storyboardIntentText(text);
        String shotSize;
        if (detail) {
            shotSize = "特写或近景，突出一个明确可见的车辆细节";
        } else if (interior) {
            shotSize = "中近景，展示座舱空间、材质和配置层次";
        } else if (conversion) {
            shotSize = "中景或全景，保留门店/车辆/咨询氛围的空间关系";
        } else if (lifestyle) {
            shotSize = "中远景或跟拍景别，展示车辆和使用场景的关系";
        } else if (exterior || opening) {
            shotSize = "全景到中景，先建立整车轮廓再突出车身线条";
        } else {
            shotSize = "中景，画面主体清楚，留出短视频裁切安全区";
        }

        String cameraMotion;
        if (containsAny(text, "环绕", "360", "orbit")) {
            cameraMotion = "平稳小幅环绕车辆，保持车身比例稳定";
        } else if (containsAny(text, "推进", "推近", "推入", "zoom in", "dolly in")) {
            cameraMotion = "慢速推进，逐步靠近展示重点";
        } else if (containsAny(text, "拉远", "后退", "zoom out", "dolly out")) {
            cameraMotion = "轻微拉远，扩大空间和车型轮廓";
        } else if (containsAny(text, "横移", "侧移", "平移", "pan", "track", "tracking")) {
            cameraMotion = "平滑横移或跟拍，运动方向保持单一";
        } else if (containsAny(text, "俯拍", "航拍", "上帝视角", "aerial", "top")) {
            cameraMotion = "轻微俯拍下探，保持车辆主体完整";
        } else if (detail) {
            cameraMotion = "锁定或微距慢推，运动幅度小，细节保持清晰";
        } else if (interior) {
            cameraMotion = "平稳横移或轻推，沿座舱结构移动";
        } else if (lifestyle) {
            cameraMotion = "顺着车辆行进方向轻跟拍，运动自然";
        } else if (conversion || closing) {
            cameraMotion = "稳定镜头轻微推进，结尾停在咨询/预约氛围上";
        } else {
            cameraMotion = "稳定慢推，避免突然换角度";
        }

        String composition;
        if (hasSceneReference) {
            composition = "沿用场景参考图的地点和空间结构，车辆占画面主要视觉位置";
        } else if (interior) {
            composition = "前景放配置或座椅，背景保留座舱纵深";
        } else if (detail) {
            composition = "细节居中或三分构图，背景保持干净虚化";
        } else if (lifestyle) {
            composition = "车辆与道路/城市/生活环境同框，主体不要被遮挡";
        } else if (conversion) {
            composition = "车辆、门店或权益氛围同框，视觉焦点简洁";
        } else {
            composition = "车辆主体居中偏三分线，保留头尾和车身比例";
        }

        String subjectAction;
        if (detail) {
            subjectAction = "只展示一个细节重点，例如灯组、轮毂、天窗、Logo、材质或车漆反光";
        } else if (interior) {
            subjectAction = "镜头从中控、座椅或后排空间依次掠过，展示舒适和配置";
        } else if (lifestyle) {
            subjectAction = "车辆在真实使用场景中自然通过或静态展示，突出代入感";
        } else if (conversion) {
            subjectAction = hostEnabled
                    ? "销售顾问可在画面边侧完成试驾邀约，车辆仍是主角"
                    : "镜头落在车辆、门店、权益氛围和咨询入口上，车辆仍是主角";
        } else if (opening) {
            subjectAction = "先让整车轮廓清楚出现，再展示车头或车身高光";
        } else {
            subjectAction = "围绕当前卖点做一个清楚的可视化展示，车辆始终是主角";
        }

        String pacing;
        if (containsAny(text, "快节奏", "快速", "卡点", "fast")) {
            pacing = "快节奏，一段内只做一到两次视觉重点转移";
        } else if (containsAny(text, "慢", "高级", "质感", "slow", "cinematic")) {
            pacing = "慢节奏，动作克制，突出质感和稳定性";
        } else if (opening) {
            pacing = "开场前两秒建立主体，随后进入展示重点";
        } else if (closing) {
            pacing = "结尾放慢半拍，给咨询和预约动作留稳定画面";
        } else {
            pacing = "中等节奏，动作连续，适合与前后片段顺序拼接";
        }

        String transition;
        if (opening) {
            transition = "从干净开场进入，不突然切换地点";
        } else if (closing) {
            transition = "结尾停在稳定画面，便于作为整条视频收束";
        } else {
            transition = "结尾保持主体、运动方向和色彩稳定，便于接下一段";
        }

        return new CarSalesShotPlan(intent, shotSize, cameraMotion, composition, subjectAction, pacing, transition);
    }

    private String shotPlanSummary(CarSalesShotPlan shotPlan) {
        if (shotPlan == null) {
            return null;
        }
        return "画面目标=" + shotPlan.intent()
                + "；景别=" + shotPlan.shotSize()
                + "；镜头运动=" + shotPlan.cameraMotion()
                + "；构图=" + shotPlan.composition()
                + "；主体动作=" + shotPlan.subjectAction()
                + "；节奏=" + shotPlan.pacing()
                + "；转场=" + shotPlan.transition();
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
                                            int index, int total, String model,
                                            SceneImageSelection imageSelection) {
        if (isEnglishNarration(request)) {
            return buildCarSalesScenePromptEnglish(request, scene, index, total, model, imageSelection);
        }
        StringBuilder prompt = new StringBuilder();
        boolean hasSceneReference = hasSceneReference(imageSelection);
        boolean multiCarCompare = isMultiCarCompareRequest(request);
        CarSalesVideoDTO.CarPackage boundCarPackage = findSceneCarPackage(request, scene);
        String sceneVisualPrompt = scene == null ? null : resolveSceneVisualPrompt(scene);
        CarSalesShotPlan shotPlan = hasSceneReference
                ? buildSceneReferenceSafeShotPlan(scene == null ? null : scene.getTitle(), sceneVisualPrompt,
                index, total, hostAppearanceEnabled(request))
                : buildCarSalesShotPlan(
                scene == null ? null : scene.getTitle(),
                sceneVisualPrompt,
                index,
                total,
                false,
                hostAppearanceEnabled(request)
        );
        prompt.append("生成汽车销售短视频第 ").append(index).append("/").append(total)
                .append(" 段，单段连续镜头，结尾稳定便于拼接。");
        prompt.append("画面文字硬性规则：当前视频模型只生成车辆、内饰和场景画面；字幕、标题、大字报、价格牌、卖点卡片和任何可读文字全部由后期系统单独处理，不得画进原生画面。");
        if (multiCarCompare) {
            appendPromptLine(prompt, "对比一致性", "统一广告质感；各车型按绑定素材独立呈现，外观、颜色、内饰和卖点不交叉");
            appendPromptLine(prompt, "车型出场顺序", multiCarCompareSummary(request));
            if (boundCarPackage != null) {
                appendPromptLine(prompt, "本段绑定车型", carPackageSummary(boundCarPackage));
            }
            if (scene != null) {
                appendPromptLine(prompt, "本段对比维度", scene.getCompareDimension());
                appendPromptLine(prompt, "本段镜头用途", scene.getShotPurpose());
            }
        } else {
            appendPromptLine(prompt, "一致性", "保持同一辆车、同一套内外饰、同一广告质感，车辆主体以参考图为准");
        }
        appendPromptLine(prompt, "跨段连续", "本段会与其他段顺序拼接，延续同一条汽车广告的车辆款型、颜色、内外饰、画面质感、转场节奏和品牌调性");
        appendPromptLine(prompt, "车型", trimPrompt(request.getBrandModel(), 120));
        appendPromptLine(prompt, "目标客户", trimPrompt(request.getAudience(), 120));
        appendPromptLine(prompt, "卖点", trimPrompt(request.getSellingPoints(), 180));
        appendPromptLine(prompt, "转化引导", trimPrompt(request.getCallToAction(), 120));
        if (scene != null) {
            appendPromptLine(prompt, "本段主题", trimPrompt(scene.getTitle(), 80));
            appendPromptLine(prompt, "镜头目标", compactSceneGoal(shotPlan, hasSceneReference));
            if (shouldGenerateNativeAudio(request)) {
                appendPromptLine(prompt, "本段口播台词", trimPrompt(quotePromptText(scene.getVoiceText()), 220));
            } else if (shouldReferenceAudio(request)) {
                appendPromptLine(prompt, "本段口播台词", trimPrompt(quotePromptText(scene.getVoiceText()), 220));
            }
        }
        appendPromptLine(prompt, "镜头", compactShotPlanSummary(shotPlan));
        appendPromptLine(prompt, "分镜边界", compactStoryboardBoundary(request, hasSceneReference));
        appendPromptLine(prompt, "参考图", compactReferenceInstruction(imageSelection, hasSceneReference));
        appendPromptLine(prompt, "音频", compactAudioInstruction(request, scene));
        appendPromptLine(prompt, "画面用途", compactVisualBoundary(request));
        appendPromptLine(prompt, "画面安全区", compactOverlaySafeArea(request));
        appendPromptLine(prompt, "补充要求", hasSceneReference
                ? seedanceSafePositiveSupplement(sceneReferenceSafeSupplement(request.getPrompt()))
                : seedanceSafePositiveSupplement(request.getPrompt()));
        return trimPrompt(prompt.toString(), CAR_SALES_SEEDANCE_PROMPT_MAX_CHARS);
    }

    private String buildCarSalesScenePromptEnglish(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene,
                                                   int index, int total, String model,
                                                   SceneImageSelection imageSelection) {
        StringBuilder prompt = new StringBuilder();
        boolean hasSceneReference = hasSceneReference(imageSelection);
        boolean multiCarCompare = isMultiCarCompareRequest(request);
        CarSalesVideoDTO.CarPackage boundCarPackage = findSceneCarPackage(request, scene);
        String sceneVisualPrompt = scene == null ? null : resolveSceneVisualPrompt(scene);
        CarSalesShotPlan shotPlan = buildEnglishShotPlan(
                scene == null ? null : scene.getTitle(),
                sceneVisualPrompt,
                index,
                total,
                hasSceneReference,
                hostAppearanceEnabled(request)
        );

        prompt.append("Generate car sales short-video segment ")
                .append(index).append("/").append(total).append(". ");
        prompt.append("Language lock: all prompt instructions, spoken narration and generated speech must stay in English only. Do not speak Chinese, display Chinese or infer Chinese lines from storyboard text. ");
        prompt.append("On-screen text ban: do not generate subtitles, narration text, title cards, banners, captions, karaoke captions, word-by-word transcript text, garbled boxes, pseudo-subtitles, speech bubbles or any readable text in the picture. Backend subtitle and headline processing happens after stitching. ");
        appendNoBgmRuleEnglish(prompt, request);
        if (hasSceneReference) {
            prompt.append("Scene reference lock: the uploaded scene reference image is the highest-priority and only source for background location, spatial layout, ground, road, wall, sky, lighting and environmental elements. Ignore storyboard, benchmark-video, narration or extra-prompt location words when they conflict with the selected scene reference; keep only camera movement, display type and sales rhythm. ");
        }
        if (!hostAppearanceEnabled(request)) {
            prompt.append("No-human rule: no person, avatar, host, sales consultant, face, body, hands, pedestrian, driver, passenger, silhouette or human-like character may appear on screen. ");
        }
        if (shouldGenerateNativeAudio(request)) {
            appendEnglishPromptLine(prompt, "Narration language",
                    "English only. The backend has normalized the segment narration to English; final spoken narration must use natural English only and must not contain Chinese words or Chinese sentences");
            appendEnglishPromptLine(prompt, "Whole-video voice lock", nativeEnglishVoiceConsistencyLock(request));
        } else if (shouldUseFinalAudio(request)) {
            prompt.append("Post-mix narration rule: the final unified narration audio will replace the video track after generation. Generate visuals only; do not create extra narration, lip-sync or spoken content not present in the final audio. ");
        }
        if (multiCarCompare) {
            prompt.append("Multi-car comparison rule: this segment belongs to one comparison video. Keep one unified ad style and rhythm, but preserve each bound vehicle as a separate identity. A single-car chapter may show only its bound vehicle; side-by-side comparison is allowed only in explicit comparison or summary segments. ");
            appendEnglishPromptLine(prompt, "Vehicle order", multiCarCompareSummaryEnglish(request));
            if (boundCarPackage != null) {
                appendEnglishPromptLine(prompt, "Bound vehicle", carPackageSummaryEnglish(boundCarPackage));
            }
            if (scene != null) {
                appendEnglishPromptLine(prompt, "Comparison dimension",
                        englishSafePromptValue(scene.getCompareDimension(), 160));
                appendEnglishPromptLine(prompt, "Shot purpose", englishSafePromptValue(scene.getShotPurpose(), 160));
            }
        } else {
            prompt.append("Cross-segment consistency rule: this segment will be stitched with the other segments in order. Keep the same vehicle model, color, interior and exterior identity, visual quality, transition rhythm, narration strategy and brand tone. Do not change the car, color, style or main subject. ");
        }

        appendEnglishPromptLine(prompt, "Vehicle", englishSafePromptValue(request.getBrandModel(), 140));
        appendEnglishPromptLine(prompt, "Target customer", englishSafePromptValue(request.getAudience(), 180));
        appendEnglishPromptLine(prompt, "Selling points", englishSafePromptValue(request.getSellingPoints(), 280));
        appendEnglishPromptLine(prompt, "Call to action", englishSafePromptValue(request.getCallToAction(), 180));
        if (scene != null) {
            appendEnglishPromptLine(prompt, "Segment topic", englishSafePromptValue(scene.getTitle(), 160));
            appendEnglishPromptLine(prompt, "Segment visual intent", hasSceneReference
                    ? sceneActionPromptForSceneReferenceEnglish(sceneVisualPrompt)
                    : shotPlanSummaryEnglish(shotPlan));
            String narration = englishNarrationForPrompt(scene.getVoiceText(), "本段口播台词");
            if (StringUtils.hasText(narration)
                    && (shouldGenerateNativeAudio(request) || shouldReferenceAudio(request))) {
                appendEnglishPromptLine(prompt, "Segment narration", narration);
            }
        }

        appendEnglishPromptLine(prompt, "Director shot plan", shotPlanSummaryEnglish(shotPlan));
        prompt.append("Single-segment execution: generate one continuous shot or one clearly controlled camera move. Establish the main subject first, complete one visual point, then end with a stable frame for stitching. Use one location and one display goal inside this segment. ");

        appendEnglishPromptLine(prompt, "Additional request", hasSceneReference
                ? sceneReferenceSafeEnglishSupplement(request.getPrompt())
                : englishSafePromptValue(request.getPrompt(), 360));

        if (hasSceneReference) {
            appendEnglishPromptLine(prompt, "Scene reference image", sceneReferenceSummaryEnglish(imageSelection));
            prompt.append("Do not invent a new showroom, store, road or city from storyboard text. Place the same reference vehicle naturally inside the selected scene reference. ");
        }

        if (hostAppearanceEnabled(request)) {
            prompt.append("Storyboard text may guide only shot type, composition rhythm, transition rhythm and presenter timing. It must not copy old vehicles, old colors, old faces, old clothing, old showrooms, old subtitle boxes or old environments. Vehicle facts must come from current reference images and vehicle information. Presenter identity, face, clothing, age impression and temperament must come from the current avatar settings. ");
        } else {
            prompt.append("If storyboard, extra prompts, reference video or narration mention people, ignore those references and convert the shot into vehicle, interior, lighting, space and usage-scene visuals. ");
        }

        if (shouldGenerateNativeAudio(request)) {
            appendEnglishPromptLine(prompt, "Voice style",
                    nativeVoiceStyleLabel(request.getNativeVoiceStyle(), hostAppearanceEnabled(request),
                            request.getNativeVoiceLanguage()));
            appendEnglishPromptLine(prompt, "Speech rhythm", nativeEnglishSpeechStyleLabel(request.getNativeSpeechStyle()));
            prompt.append("Voice consistency rule: keep the same speaker voice, gender impression, age impression, accent, emotion intensity, pitch and speaking speed for this entire segment. Do not switch speakers, change gender, change timbre or add a second narrator. ");
            prompt.append("Strict narration rule: the quoted English segment narration is the only spoken content source. Read it in English as written. Do not translate it to Chinese, do not insert Chinese, do not add selling points, rewrite, merge or repeat other segments. Do not draw narration text on screen. ");
            if (isStrictVoiceText(request)) {
                prompt.append("Strict voice-text mode: old storyboard lines are only used to allocate the current script into segments. Old lines must not appear in visuals, speech, subtitles or lip-sync. ");
            }
        }

        if (shouldReferenceAudio(request)) {
            prompt.append("Reference-audio rule: speech, lip-sync and rhythm must follow the provided reference audio. Do not generate subtitles in the picture. If segment narration is provided, express only that narration with the reference audio and do not rewrite it from storyboard or extra prompts. ");
        } else if (shouldUseFinalAudio(request)) {
            prompt.append("If segment narration is provided, the visuals should align with that line only. Do not generate subtitle text in the picture. ");
        } else if (StringUtils.hasText(request.getBgmUrl())) {
            prompt.append("BGM rule: background music will be mixed separately after generation. Do not treat BGM as narration or a subtitle source. Do not generate subtitle text in the picture. ");
        }

        appendPostMixHostVisualRule(prompt, request);
        if (hostAppearanceEnabled(request)) {
            if (StringUtils.hasText(request.getHostImageUrl())) {
                prompt.append("An avatar reference image is provided. When a presenter appears, keep the same sales consultant appearance, temperament, age impression, hairstyle, clothing style, position logic and screen presence. Do not replace the person. ");
            } else {
                prompt.append("A virtual presenter is enabled but no avatar reference image is provided. Let the presenter appear only when the explanation or appointment cue truly needs it; keep one consistent consultant and do not make every shot presenter-led. ");
            }
        }
        if (hostAppearanceEnabled(request) && !isSeedance2(model) && StringUtils.hasText(request.getHostImageUrl())) {
            prompt.append("This model uses first-frame image-to-video, so the avatar reference is a visual description only and is not passed as a multi-reference image. ");
        }
        if (StringUtils.hasText(request.getHostVideoUrl())) {
            prompt.append("Adapt the visual style to the selected video material for later editing. ");
        }
        if (multiCarCompare) {
            prompt.append("All segments must look like one comparison ad. Keep a unified ad quality. Each vehicle may use only its own reference images; never merge or swap appearance, color, interior or selling points across vehicles. ");
        } else {
            prompt.append("All segments must feel like one shoot. Keep the same car, same interior and exterior identity, same visual style and same ad quality. The vehicle subject must follow the reference images; avoid distortion, model drift and unrelated brand marks. ");
        }
        return ensureEnglishPromptNoCjk(trimPrompt(prompt.toString(), 2400));
    }

    private void appendPostMixHostVisualRule(StringBuilder prompt, CarSalesVideoDTO request) {
        if (prompt == null || !hostAppearanceEnabled(request) || !shouldUseFinalAudio(request)) {
            return;
        }
        prompt.append("Post-mix presenter rule: final narration audio will be added after generation, so any presenter on screen must not visibly speak, lip-sync, sing or mouth words. Use listening poses, pointing gestures, product demonstration gestures and neutral closed-mouth expressions only. ");
    }

    private String compactShotPlanSummary(CarSalesShotPlan shotPlan) {
        if (shotPlan == null) {
            return null;
        }
        return trimPrompt(String.join("；",
                "景别=" + seedanceSafeVisualText(shotPlan.shotSize()),
                "运动=" + seedanceSafeVisualText(shotPlan.cameraMotion()),
                "构图=" + seedanceSafeVisualText(shotPlan.composition()),
                "动作=" + seedanceSafeVisualText(shotPlan.subjectAction()),
                "节奏=" + seedanceSafeVisualText(shotPlan.pacing())
        ), 260);
    }

    private String compactSceneGoal(CarSalesShotPlan shotPlan, boolean hasSceneReference) {
        if (shotPlan == null) {
            return null;
        }
        String intent = seedanceSafeVisualText(shotPlan.intent());
        if (!StringUtils.hasText(intent)) {
            return null;
        }
        String prefix = hasSceneReference ? "以场景参考图为背景，" : "";
        return trimPrompt(prefix + intent + "，单一展示目标，镜头连贯稳定", 180);
    }

    private String compactReferenceInstruction(SceneImageSelection imageSelection, boolean hasSceneReference) {
        String selected = selectedReferenceSummary(imageSelection);
        if (hasSceneReference) {
            String scene = trimPrompt(sceneReferenceSummary(imageSelection), 120);
            String base = "背景地点、空间、地面和光线以场景参考图为准，车辆自然进入场景";
            return StringUtils.hasText(scene) ? base + "；场景=" + scene : base;
        }
        String base = "首帧参考图锁定车辆/内饰外观、颜色、构图和质感";
        return StringUtils.hasText(selected) ? base + "；本段参考=" + selected : base;
    }

    private String compactStoryboardBoundary(CarSalesVideoDTO request, boolean hasSceneReference) {
        StringBuilder value = new StringBuilder();
        value.append("分镜只指导镜头类型、景别、构图节奏和转场节奏；车辆事实以当前参考图和车型资料为准");
        if (hasSceneReference) {
            value.append("；地点背景和光线以场景参考图为准");
        }
        if (hostAppearanceEnabled(request)) {
            value.append("；销售顾问外观以当前数字人参考图或设置为准");
        } else {
            value.append("；画面以车辆、内饰、细节和场景光线为主");
        }
        return trimPrompt(value.toString(), 260);
    }

    private String selectedReferenceSummary(SceneImageSelection imageSelection) {
        if (imageSelection == null || imageSelection.labels() == null) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String label : imageSelection.labels()) {
            if (StringUtils.hasText(label)) {
                labels.add(label.trim());
            }
        }
        return labels.isEmpty() ? null : trimPrompt(String.join("、", labels), 120);
    }

    private String compactAudioInstruction(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene) {
        if (request == null) {
            return null;
        }
        if (shouldReferenceAudio(request)) {
            return "参考音频控制口播和节奏；本段聚焦画面运动和产品展示";
        }
        if (shouldGenerateNativeAudio(request)) {
            String narration = quotePromptText(scene == null ? null : scene.getVoiceText());
            String base = compactNativeVoiceLanguage(request.getNativeVoiceLanguage()) + "原生口播，全片同一音色和自然语速";
            return StringUtils.hasText(narration) ? trimPrompt(base + "；台词=" + narration, 220) : base;
        }
        if (shouldUseFinalAudio(request)) {
            return hostAppearanceEnabled(request)
                    ? "成片阶段合成统一口播；本段聚焦产品画面，销售顾问以自然演示动作辅助"
                    : "成片阶段合成统一口播；本段聚焦产品画面和镜头运动";
        }
        if (StringUtils.hasText(request.getBgmUrl())) {
            return "成片阶段合成背景音乐；本段控制画面节奏";
        }
        return "No narration or BGM: generate silent visuals only; do not speak, lip-sync, sing, create sound effects, ambient audio or background music.";
    }

    private String compactNativeVoiceLanguage(String language) {
        return isEnglishLanguage(language) ? "英语" : "中文普通话";
    }

    private String compactVisualBoundary(CarSalesVideoDTO request) {
        String cleanFrame = "产品级车辆展示画面，背景留白干净，适合后期合成；后期图文层由系统单独合成，模型原生画面保持干净安全留白，不出现可读文字或大型字块";
        if (hostAppearanceEnabled(request)) {
            if (StringUtils.hasText(request == null ? null : request.getHostImageUrl())) {
                return "车辆为主；销售顾问以数字人参考图为准自然辅助；" + cleanFrame;
            }
            return "车辆为主；销售顾问自然辅助讲解；" + cleanFrame;
        }
        return "车辆、内饰、场景光线为视觉重点；" + cleanFrame;
    }

    private String compactOverlaySafeArea(CarSalesVideoDTO request) {
        if (isWideAspectRatio(request == null ? null : request.getAspectRatio())) {
            return "横屏主体保持完整，避开上下边缘和左右极限边，给后期字幕与标题留出可读空间";
        }
        return "竖屏主体保持完整，车头/人脸/Logo 避开底部字幕区和顶部大字区，左右保留边距";
    }

    private String seedanceSafePositiveSupplement(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text) || containsNegativePromptInstruction(text)) {
            return null;
        }
        if (containsOnScreenTextCue(text)) {
            return null;
        }
        return trimPrompt(seedanceSafeVisualText(text), 120);
    }

    private String seedanceSafeVisualText(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        text = text.replace("主体不要被遮挡", "主体清晰完整")
                .replace("避免突然换角度", "运动连续稳定")
                .replace("只展示一个细节重点", "聚焦一个细节重点")
                .replace("只展示一个细节", "聚焦一个细节")
                .replace("只做一到两次", "完成一到两次")
                .replace("只安排", "安排")
                .replace("只使用", "使用")
                .replace("不得", "")
                .replace("禁止", "")
                .replace("绝对", "")
                .replace("不要", "")
                .replace("忽略", "")
                .replace("强字幕大字报", "")
                .replace("字幕大字报", "")
                .replace("字幕/大字报后期建议", "后期图文建议")
                .replace("字幕 / 大字报后期建议", "后期图文建议")
                .replace("字幕和大字报", "后期图文层")
                .replace("大字报和字幕", "后期图文层")
                .replace("大字报", "")
                .replace("字幕", "")
                .replace("标题卡", "")
                .replace("标题条", "")
                .replace("标题", "")
                .replace("价格牌", "")
                .replace("价格贴纸", "")
                .replace("贴纸", "")
                .replace("卖点卡片", "卖点镜头")
                .replace("文案", "")
                .replace("可读文字", "")
                .replace("文字", "")
                .replace("big text", "")
                .replace("Big text", "")
                .replace("subtitle", "")
                .replace("Subtitle", "")
                .replace("caption", "")
                .replace("Caption", "")
                .replace("headline", "")
                .replace("Headline", "")
                .replace("title card", "")
                .replace("Title card", "")
                .replace("text overlay", "")
                .replace("Text overlay", "")
                .replace("无字幕", "")
                .replace("无人像", "")
                .replace("无人", "")
                .replace("路人", "")
                .replace("手部", "");
        return trimToNull(text);
    }

    private boolean containsOnScreenTextCue(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "字幕", "大字报", "标题", "文案", "文字", "贴纸", "价格牌", "卖点卡片",
                "subtitle", "caption", "headline", "title card", "big text", "text overlay");
    }

    private boolean containsNegativePromptInstruction(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "不要", "不得", "禁止", "绝对", "无字幕", "无人像", "无人", "无人物",
                "手部", "路人", "行人", "不得出现", "不要出现",
                "no ", "don't", "do not", "without", "avoid", "ban", "forbid");
    }

    private boolean hasSceneReference(SceneImageSelection imageSelection) {
        return imageSelection != null
                && imageSelection.roles() != null
                && imageSelection.roles().stream().anyMatch(CAR_SCENE_REFERENCE_ROLES::contains);
    }

    private String sceneReferenceSummary(SceneImageSelection imageSelection) {
        if (imageSelection == null || imageSelection.roles() == null) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < imageSelection.roles().size(); i++) {
            String role = imageSelection.roles().get(i);
            if (!CAR_SCENE_REFERENCE_ROLES.contains(role)) {
                continue;
            }
            String label = imageSelection.labels() != null && i < imageSelection.labels().size()
                    ? imageSelection.labels().get(i)
                    : null;
            labels.add(StringUtils.hasText(label) ? label : CAR_ROLE_LABELS.getOrDefault(role, role));
        }
        return labels.isEmpty() ? null : String.join("、", labels);
    }

    private String sceneReferenceSummaryEnglish(SceneImageSelection imageSelection) {
        if (imageSelection == null || imageSelection.roles() == null) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String role : imageSelection.roles()) {
            if (!CAR_SCENE_REFERENCE_ROLES.contains(role)) {
                continue;
            }
            labels.add(switch (role) {
                case "scene_showroom" -> "showroom scene reference";
                case "scene_outdoor" -> "outdoor scene reference";
                case "scene_road" -> "road scene reference";
                case "scene_night" -> "night scene reference";
                default -> "scene reference";
            });
        }
        return labels.isEmpty() ? null : String.join(", ", labels);
    }

    private String sceneActionPromptForSceneReference(String visualPrompt) {
        String text = trimToNull(visualPrompt);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        CarSalesShotPlan shotPlan = buildSceneReferenceSafeShotPlan(null, text, 1, 1, true);
        return trimPrompt("地点/背景/环境以场景参考图为准；只使用分镜中的镜头运动、展示类型和销售节奏：" + shotPlanSummary(shotPlan), 700);
    }

    private String sceneActionPromptForSceneReferenceEnglish(String visualPrompt) {
        String text = trimToNull(visualPrompt);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        CarSalesShotPlan shotPlan = buildEnglishShotPlan(null, text, 1, 1, true, true);
        return trimPrompt("Use the uploaded scene reference as the only source for location, background and environment. Keep only storyboard camera movement, display type and sales rhythm: "
                + shotPlanBriefEnglish(shotPlan), 420);
    }

    private String sanitizeScenePromptForSceneReference(String visualPrompt, boolean hostEnabled) {
        String text = trimToNull(visualPrompt);
        if (!StringUtils.hasText(text)) {
            return text;
        }
        CarSalesShotPlan shotPlan = buildSceneReferenceSafeShotPlan(null, text, 1, 1, hostEnabled);
        return trimPrompt("只保留分镜的镜头运动、景别、展示类型和销售节奏；地点、背景、环境、光线、地面、道路、墙面和空间结构全部以用户选择的场景参考图为准；"
                + shotPlanSummary(shotPlan), 700);
    }

    private CarSalesShotPlan buildSceneReferenceSafeShotPlan(String title, String visualPrompt, int index, int total,
                                                            boolean hostEnabled) {
        String text = ((title == null ? "" : title) + " "
                + (visualPrompt == null ? "" : visualPrompt)).trim().toLowerCase(Locale.ROOT);
        int safeTotal = Math.max(1, total);
        int safeIndex = Math.max(1, Math.min(index, safeTotal));
        boolean interior = containsAny(text, "内饰", "座椅", "中控", "空间", "前排", "后排", "方向盘", "仪表", "后备箱", "天窗",
                "interior", "seat", "dashboard", "trunk", "sunroof");
        boolean detail = containsAny(text, "车灯", "灯光", "轮毂", "天窗", "logo", "标识", "细节", "材质", "特写",
                "light", "wheel", "sunroof", "detail", "close", "macro");
        boolean exterior = containsAny(text, "外观", "车头", "车身", "整车", "正面", "侧面", "背面", "环绕",
                "exterior", "front", "side", "rear");
        boolean conversion = containsAny(text, "到店", "试驾", "邀约", "联系", "咨询", "转化", "优惠",
                "cta", "offer", "appointment", "contact");
        boolean lifestyle = containsAny(text, "通勤", "出行", "家庭", "驾驶", "用车",
                "commute", "family", "drive", "lifestyle");
        boolean opening = safeIndex == 1 || containsAny(text, "开场", "介绍", "打招呼", "hello", "hi");
        boolean closing = safeIndex == safeTotal || containsAny(text, "收口", "结尾", "关注", "预约", "下单", "closing");

        List<String> intents = new ArrayList<>();
        if (interior) {
            intents.add("展示车辆内饰空间与舒适配置");
        }
        if (detail) {
            intents.add("展示车辆细节特写");
        }
        if (exterior) {
            intents.add("展示车辆外观与车身线条");
        }
        if (conversion) {
            intents.add("保留咨询和转化节奏但不指定地点");
        }
        if (lifestyle) {
            intents.add("展示车辆使用氛围但地点以场景图为准");
        }
        if (opening) {
            intents.add("开场建立车辆主体");
        }
        String intent = intents.isEmpty() ? "按当前口播安排车辆展示节奏" : String.join("，", intents);

        String shotSize;
        if (detail) {
            shotSize = "特写或近景，突出一个明确可见的车辆细节";
        } else if (interior) {
            shotSize = "中近景，展示座舱空间、材质和配置层次";
        } else if (exterior || opening) {
            shotSize = "全景到中景，先建立整车轮廓再突出车身线条";
        } else {
            shotSize = "中景或全景，让车辆自然进入用户选择的场景图空间";
        }

        String cameraMotion;
        if (containsAny(text, "环绕", "360", "orbit")) {
            cameraMotion = "平稳小幅环绕车辆，保持车身比例稳定";
        } else if (containsAny(text, "推进", "推近", "推入", "zoom in", "dolly in")) {
            cameraMotion = "慢速推进，逐步靠近展示重点";
        } else if (containsAny(text, "拉远", "后退", "zoom out", "dolly out")) {
            cameraMotion = "轻微拉远，扩大空间和车型轮廓";
        } else if (containsAny(text, "横移", "侧移", "平移", "pan", "track", "tracking")) {
            cameraMotion = "平滑横移或跟拍，运动方向保持单一";
        } else if (containsAny(text, "俯拍", "航拍", "上帝视角", "aerial", "top")) {
            cameraMotion = "轻微俯拍下探，保持车辆主体完整";
        } else if (detail) {
            cameraMotion = "锁定或微距慢推，运动幅度小，细节保持清晰";
        } else if (interior) {
            cameraMotion = "平稳横移或轻推，沿座舱结构移动";
        } else {
            cameraMotion = "稳定慢推，避免突然换角度";
        }

        String composition = "以场景参考图作为唯一地点和背景来源，车辆占主要视觉位置";
        String subjectAction;
        if (detail) {
            subjectAction = "只展示一个细节重点，例如灯组、轮毂、天窗、Logo、材质或车漆反光";
        } else if (interior) {
            subjectAction = "镜头从中控、座椅或后排空间依次掠过，展示舒适和配置";
        } else if (conversion && hostEnabled) {
            subjectAction = "销售顾问只在需要时弱出镜完成咨询或预约节奏，车辆仍是主角";
        } else {
            subjectAction = "围绕当前卖点做清楚的车辆展示，背景完全沿用用户选择的场景参考图";
        }

        String pacing;
        if (containsAny(text, "快节奏", "快速", "卡点", "fast")) {
            pacing = "快节奏，一段内只做一到两次视觉重点转移";
        } else if (containsAny(text, "慢", "高级", "质感", "slow", "cinematic")) {
            pacing = "慢节奏，动作克制，突出质感和稳定性";
        } else if (opening) {
            pacing = "开场前两秒建立主体，随后进入展示重点";
        } else if (closing) {
            pacing = "结尾放慢半拍，给咨询和预约动作留稳定画面";
        } else {
            pacing = "中等节奏，动作连续，适合与前后片段顺序拼接";
        }

        String transition = closing
                ? "结尾停在稳定画面，便于作为整条视频收束"
                : "结尾保持主体、运动方向和色彩稳定，便于接下一段";
        return new CarSalesShotPlan(intent, shotSize, cameraMotion, composition, subjectAction, pacing, transition);
    }

    private CarSalesShotPlan buildEnglishShotPlan(String title, String visualPrompt, int index, int total,
                                                  boolean hasSceneReference, boolean hostEnabled) {
        String text = ((title == null ? "" : title) + " "
                + (visualPrompt == null ? "" : visualPrompt)).trim().toLowerCase(Locale.ROOT);
        int safeTotal = Math.max(1, total);
        int safeIndex = Math.max(1, Math.min(index, safeTotal));
        boolean interior = containsAny(text, "内饰", "座椅", "中控", "空间", "前排", "后排", "方向盘", "仪表", "后备箱", "天窗",
                "interior", "seat", "dashboard", "trunk", "sunroof");
        boolean detail = containsAny(text, "车灯", "灯光", "轮毂", "天窗", "logo", "标识", "细节", "材质", "特写",
                "light", "wheel", "sunroof", "detail", "close", "macro");
        boolean exterior = containsAny(text, "外观", "车头", "车身", "整车", "正面", "侧面", "背面", "环绕",
                "exterior", "front", "side", "rear");
        boolean conversion = containsAny(text, "展厅", "门店", "到店", "试驾", "邀约", "联系", "咨询", "转化", "优惠",
                "showroom", "store", "dealer", "cta", "offer", "appointment", "contact");
        boolean lifestyle = containsAny(text, "户外", "城市", "公路", "道路", "山路", "夜景", "通勤", "出行", "家庭",
                "outdoor", "city", "road", "night", "drive", "commute", "family", "lifestyle");
        boolean opening = safeIndex == 1 || containsAny(text, "开场", "介绍", "打招呼", "hello", "hi");
        boolean closing = safeIndex == safeTotal || containsAny(text, "收口", "结尾", "关注", "预约", "下单", "closing");

        List<String> intents = new ArrayList<>();
        if (interior) {
            intents.add("show cabin space and comfort features");
        }
        if (detail) {
            intents.add("show a clear vehicle detail close-up");
        }
        if (exterior) {
            intents.add("show exterior shape and body lines");
        }
        if (conversion) {
            intents.add("keep a clear sales conversion cue without changing the selected location");
        }
        if (lifestyle) {
            intents.add(hasSceneReference
                    ? "show the vehicle naturally inside the selected scene reference"
                    : "show real-use driving atmosphere");
        }
        if (opening) {
            intents.add("establish the vehicle at the opening");
        }
        String intent = intents.isEmpty()
                ? "match the current narration with one clean car-focused visual beat"
                : String.join(", ", intents);

        String shotSize;
        if (detail) {
            shotSize = "close-up or near shot focused on one clear vehicle detail";
        } else if (interior) {
            shotSize = "medium-close shot showing cabin space, materials and configuration layers";
        } else if (hasSceneReference) {
            shotSize = "medium or wide shot placing the vehicle naturally inside the uploaded scene reference";
        } else if (lifestyle) {
            shotSize = "medium-wide or tracking shot showing the vehicle in use";
        } else if (exterior || opening) {
            shotSize = "wide to medium shot, establish the full vehicle shape first and then emphasize body lines";
        } else {
            shotSize = "medium shot with a clear vehicle subject and safe vertical-video framing";
        }

        String cameraMotion;
        if (containsAny(text, "环绕", "360", "orbit")) {
            cameraMotion = "smooth small orbit around the vehicle while keeping body proportions stable";
        } else if (containsAny(text, "推进", "推近", "推入", "zoom in", "dolly in")) {
            cameraMotion = "slow dolly in toward the display focus";
        } else if (containsAny(text, "拉远", "后退", "zoom out", "dolly out")) {
            cameraMotion = "slight dolly out to reveal space and vehicle outline";
        } else if (containsAny(text, "横移", "侧移", "平移", "pan", "track", "tracking")) {
            cameraMotion = "smooth lateral move or tracking move in one stable direction";
        } else if (containsAny(text, "俯拍", "航拍", "上帝视角", "aerial", "top")) {
            cameraMotion = "slight high-angle move while keeping the full vehicle readable";
        } else if (detail) {
            cameraMotion = "locked or macro slow push with minimal movement and crisp detail";
        } else if (interior) {
            cameraMotion = "stable lateral move or light push through the cabin structure";
        } else if (lifestyle && !hasSceneReference) {
            cameraMotion = "natural tracking movement following the vehicle direction";
        } else {
            cameraMotion = "stable slow push with no sudden angle change";
        }

        String composition = hasSceneReference
                ? "use the uploaded scene reference as the only source for location and spatial structure, with the vehicle in the main visual position"
                : "keep the vehicle centered or on a clean rule-of-thirds position with full body proportions";
        String subjectAction;
        if (detail) {
            subjectAction = "show one detail focus such as lighting, wheels, logo, material or paint reflection";
        } else if (interior) {
            subjectAction = "move across the console, seats or rear space to show comfort and configuration";
        } else if (hasSceneReference) {
            subjectAction = hostEnabled && conversion
                    ? "a presenter may appear only as a subtle consultation cue when needed; the vehicle remains the hero"
                    : "show the vehicle clearly around the current selling point; the selected scene reference controls the environment";
        } else if (lifestyle) {
            subjectAction = "let the vehicle pass naturally or hold as a static hero inside a real-use context";
        } else if (conversion) {
            subjectAction = hostEnabled
                    ? "a sales consultant may support the appointment cue at the edge of the frame; the vehicle remains the hero"
                    : "focus on the vehicle and sales benefit cue; the vehicle remains the hero";
        } else if (opening) {
            subjectAction = "make the full vehicle outline appear clearly first, then show a front or body highlight";
        } else {
            subjectAction = "visualize the current selling point clearly while keeping the vehicle as the hero";
        }

        String pacing;
        if (containsAny(text, "快节奏", "快速", "卡点", "fast")) {
            pacing = "fast rhythm with only one or two visual focus shifts in the segment";
        } else if (containsAny(text, "慢", "高级", "质感", "slow", "cinematic")) {
            pacing = "slow controlled rhythm emphasizing quality and stability";
        } else if (opening) {
            pacing = "establish the subject in the first two seconds, then move into the display point";
        } else if (closing) {
            pacing = "slow down slightly at the end and leave a stable consultation or appointment frame";
        } else {
            pacing = "medium rhythm with continuous motion suitable for stitching with adjacent segments";
        }

        String transition = closing
                ? "end on a stable frame for the whole-video close"
                : "end with stable subject, motion direction and color for the next segment";
        return new CarSalesShotPlan(intent, shotSize, cameraMotion, composition, subjectAction, pacing, transition);
    }

    private String shotPlanSummaryEnglish(CarSalesShotPlan shotPlan) {
        if (shotPlan == null) {
            return null;
        }
        return "visual goal=" + shotPlan.intent()
                + "; shot size=" + shotPlan.shotSize()
                + "; camera movement=" + shotPlan.cameraMotion()
                + "; composition=" + shotPlan.composition()
                + "; subject action=" + shotPlan.subjectAction()
                + "; pacing=" + shotPlan.pacing()
                + "; transition=" + shotPlan.transition();
    }

    private String shotPlanBriefEnglish(CarSalesShotPlan shotPlan) {
        if (shotPlan == null) {
            return null;
        }
        return "shot size=" + shotPlan.shotSize()
                + "; camera movement=" + shotPlan.cameraMotion()
                + "; subject action=" + shotPlan.subjectAction()
                + "; pacing=" + shotPlan.pacing();
    }

    private boolean isMultiCarCompareRequest(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        return "multi_car_compare".equalsIgnoreCase(trimToNull(request.getTaskMode()))
                || "multi_car_compare".equalsIgnoreCase(trimToNull(request.getRenderMode()))
                || (request.getCarPackages() != null && request.getCarPackages().size() >= 2);
    }

    private String carPackageSummary(CarSalesVideoDTO.CarPackage carPackage) {
        if (carPackage == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(carPackage.getBrandModel())) {
            parts.add("车型=" + carPackage.getBrandModel().trim());
        }
        if (StringUtils.hasText(carPackage.getColor())) {
            parts.add("颜色=" + carPackage.getColor().trim());
        }
        if (StringUtils.hasText(carPackage.getRole())) {
            parts.add("角色=" + carPackage.getRole().trim());
        }
        if (StringUtils.hasText(carPackage.getSellingPoints())) {
            parts.add("卖点=" + carPackage.getSellingPoints().trim());
        }
        if (StringUtils.hasText(carPackage.getMaterialCompleteness())) {
            parts.add("素材完整度=" + carPackage.getMaterialCompleteness().trim());
        }
        return String.join("；", parts);
    }

    private String multiCarCompareSummary(CarSalesVideoDTO request) {
        if (request == null || request.getCarPackages() == null || request.getCarPackages().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
            if (carPackage == null) {
                continue;
            }
            String fallback = "车型" + (carPackage.getCarIndex() == null ? lines.size() + 1 : carPackage.getCarIndex() + 1);
            String name = StringUtils.hasText(carPackage.getBrandModel())
                    ? carPackage.getBrandModel().trim()
                    : trimToDefault(carPackage.getPackageName(), fallback);
            lines.add((carPackage.getCarIndex() == null ? lines.size() + 1 : carPackage.getCarIndex() + 1)
                    + ". " + name
                    + (StringUtils.hasText(carPackage.getRole()) ? "（" + carPackage.getRole().trim() + "）" : ""));
        }
        return String.join("；", lines);
    }

    private String carPackageSummaryEnglish(CarSalesVideoDTO.CarPackage carPackage) {
        if (carPackage == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("vehicle=" + englishCarPackageName(carPackage, 1));
        String color = englishSafePromptValue(carPackage.getColor(), 80);
        if (StringUtils.hasText(color)) {
            parts.add("color=" + color);
        }
        String role = englishSafePromptValue(carPackage.getRole(), 80);
        if (StringUtils.hasText(role)) {
            parts.add("role=" + role);
        }
        String sellingPoints = englishSafePromptValue(carPackage.getSellingPoints(), 180);
        if (StringUtils.hasText(sellingPoints)) {
            parts.add("selling points=" + sellingPoints);
        }
        String completeness = englishSafePromptValue(carPackage.getMaterialCompleteness(), 80);
        if (StringUtils.hasText(completeness)) {
            parts.add("asset completeness=" + completeness);
        }
        return String.join("; ", parts);
    }

    private String multiCarCompareSummaryEnglish(CarSalesVideoDTO request) {
        if (request == null || request.getCarPackages() == null || request.getCarPackages().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
            if (carPackage == null) {
                continue;
            }
            int displayIndex = carPackage.getCarIndex() == null ? lines.size() + 1 : carPackage.getCarIndex() + 1;
            String name = englishCarPackageName(carPackage, displayIndex);
            String role = englishSafePromptValue(carPackage.getRole(), 80);
            lines.add(displayIndex + ". " + name + (StringUtils.hasText(role) ? " (" + role + ")" : ""));
        }
        return String.join("; ", lines);
    }

    private String englishCarPackageName(CarSalesVideoDTO.CarPackage carPackage, int fallbackIndex) {
        if (carPackage == null) {
            return "Car " + Math.max(1, fallbackIndex);
        }
        String brandModel = englishSafePromptValue(carPackage.getBrandModel(), 120);
        if (StringUtils.hasText(brandModel)) {
            return brandModel;
        }
        String packageName = englishSafePromptValue(carPackage.getPackageName(), 120);
        if (StringUtils.hasText(packageName)) {
            return packageName;
        }
        return "Car " + Math.max(1, fallbackIndex);
    }

    private String visualScriptContextForPrompt(CarSalesVideoDTO request) {
        return visualScriptContextForPrompt(request, false);
    }

    private String visualScriptContextForPrompt(CarSalesVideoDTO request, boolean hasSceneReference) {
        String context = trimToNull(request == null ? null : request.getScriptContext());
        if (!StringUtils.hasText(context)) {
            return null;
        }
        if (hasSceneReference || isEnglishNarration(request)) {
            return null;
        }
        if (shouldGenerateNativeAudio(request) || hasSelectedVoiceAudio(request)) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String paragraph : context.split("\\R{2,}|\\r?\\n")) {
            String line = paragraph == null ? "" : paragraph.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String normalized = line.toLowerCase();
            if (normalized.startsWith("内容主导")
                    || normalized.contains("新口播文案")
                    || normalized.contains("用于替换分镜旧台词")) {
                continue;
            }
            kept.add(line);
        }
        return trimPrompt(String.join("\n", kept), 500);
    }

    private void appendPromptLine(StringBuilder prompt, String label, String value) {
        if (StringUtils.hasText(value)) {
            prompt.append(label).append("：").append(value.trim()).append("。");
        }
    }

    private void appendNoBgmRule(StringBuilder prompt, CarSalesVideoDTO request) {
        if (StringUtils.hasText(request == null ? null : request.getBgmUrl())) {
            prompt.append("Post-production BGM rule: the user selected a BGM asset, but background music will be mixed only by the backend after all video segments, stitching and narration processing are complete. The video model must not generate, keep or imitate any background music, instrumental track, beat, jingle, music bed, intro/outro music, ambient music or advertising music during this generation stage. ");
            prompt.append("BGM must not be used as narration, subtitles, lip-sync or visual timing source; generate visuals and required speech only. ");
            return;
        }
        prompt.append("背景音乐硬性禁令：用户未选择 BGM，本任务最终不会后期混入背景音乐；视频模型也不得生成或保留任何背景音乐、配乐、伴奏、节拍、音效铺底、片头片尾音乐、环境音乐或广告音乐。");
        prompt.append("如果需要原生口播，只允许干净单人声；如果是后期口播或无口播模式，当前阶段只生成画面，不要生成任何音乐声。");
        if (!hasFinalNarrationAudio(request)) {
            prompt.append("无音频规则：当前任务没有口播或 BGM，只生成画面，不要说话、对口型、唱歌、生成音效、环境声或背景音乐。");
        }
    }

    private void appendEnglishPromptLine(StringBuilder prompt, String label, String value) {
        if (StringUtils.hasText(value)) {
            prompt.append(label).append(": ").append(value.trim()).append(". ");
        }
    }

    private void appendNoBgmRuleEnglish(StringBuilder prompt, CarSalesVideoDTO request) {
        if (StringUtils.hasText(request == null ? null : request.getBgmUrl())) {
            prompt.append("Post-production BGM rule: the user selected a BGM asset, but background music will be mixed only by the backend after all video segments, stitching and narration processing are complete. The video model must not generate, keep or imitate any background music, instrumental track, beat, jingle, music bed, intro/outro music, ambient music or advertising music during this generation stage. ");
            prompt.append("BGM must not be used as narration, subtitles, lip-sync or visual timing source; generate visuals and required speech only. ");
            return;
        }
        prompt.append("No-BGM hard rule: the user did not select background music, so the backend will not mix any BGM after generation. The video model must not generate or keep any background music, instrumental track, beat, jingle, music bed, intro/outro music, ambient music or advertising music. ");
        prompt.append("If native narration is required, output clean single-speaker voice only. If post-mix narration or no narration is used, generate visuals only and no music audio. ");
        if (!hasFinalNarrationAudio(request)) {
            prompt.append("No-audio rule: generate visuals only; do not speak, lip-sync, sing, create sound effects, ambient audio or background music. ");
        }
    }

    private String englishSafePromptValue(String value, int maxLength) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        text = trimPrompt(text.replace('：', ':').replace('；', ';').replace('，', ','), maxLength);
        return containsCjk(text) ? null : text;
    }

    private String englishNarrationForPrompt(String value, String fieldName) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (containsCjk(text)) {
            throw new BusinessException(50214,
                    "ENGLISH_PROMPT_LANGUAGE_MISMATCH: 已选择英语讲述，但" + fieldName + "仍包含中文，已停止生成以避免中文分镜或口播污染模型");
        }
        return quotePromptText(text);
    }

    private String ensureEnglishPromptNoCjk(String prompt) {
        if (containsCjk(prompt)) {
            throw new BusinessException(50214,
                    "ENGLISH_PROMPT_LANGUAGE_MISMATCH: 已选择英语讲述，但视频生成提示词仍包含中文，已停止生成以避免模型中英混播");
        }
        return prompt;
    }

    private String sceneReferenceSafeSupplement(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (containsSceneEnvironmentReference(text)) {
            return null;
        }
        return trimPrompt(text, 400);
    }

    private String sceneReferenceSafeEnglishSupplement(String value) {
        String text = englishSafePromptValue(value, 360);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (containsSceneEnvironmentReference(text)) {
            return null;
        }
        return text;
    }

    private boolean containsSceneEnvironmentReference(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text)) {
            return false;
        }
        text = text.toLowerCase(Locale.ROOT);
        return containsAny(text,
                "地点", "背景", "环境", "场景", "展厅", "门店", "店内", "玻璃墙", "瓷砖", "地面", "墙面", "天空",
                "户外", "城市", "公路", "道路", "街道", "山路", "夜景", "停车场", "location", "background",
                "environment", "scene", "showroom", "dealership", "dealer", "store", "glass wall", "tile",
                "floor", "wall", "sky", "outdoor", "city", "road", "street", "night", "parking");
    }

    private String normalizeSubtitle(String value) {
        return cleanSpeechText(value);
    }

    private boolean isNoSubtitle(String subtitle) {
        return SUBTITLE_MODE_NONE.equals(subtitle);
    }

    private boolean isAutoSubtitle(String subtitle) {
        return SUBTITLE_MODE_AUTO.equals(subtitle);
    }

    private boolean isUploadSubtitleMode(CarSalesVideoDTO request) {
        return request != null
                && StringUtils.hasText(request.getSubtitleMode())
                && "upload".equalsIgnoreCase(request.getSubtitleMode().trim());
    }

    private boolean isPostAutoSubtitleMode(CarSalesVideoDTO request) {
        return request != null
                && StringUtils.hasText(request.getSubtitleMode())
                && "auto".equalsIgnoreCase(request.getSubtitleMode().trim());
    }

    private boolean isCustomSubtitleMode(CarSalesVideoDTO request) {
        return request != null
                && StringUtils.hasText(request.getSubtitleMode())
                && "custom".equalsIgnoreCase(request.getSubtitleMode().trim());
    }

    private void normalizeSubtitleRequest(CarSalesVideoDTO request) {
        if (request == null) {
            return;
        }
        String subtitle = normalizeSubtitle(request.getSubtitle());
        String mode = trimToNull(request.getSubtitleMode());
        String normalizedMode = StringUtils.hasText(mode) ? mode.toLowerCase(Locale.ROOT) : null;

        if ("off".equals(normalizedMode) || isNoSubtitle(subtitle)) {
            request.setSubtitleMode("off");
            request.setSubtitle(SUBTITLE_MODE_NONE);
            return;
        }

        if ("custom".equals(normalizedMode) || "upload".equals(normalizedMode)) {
            if (!hasExplicitSubtitleText(subtitle)) {
                throw new BusinessException(40000, "自定义字幕模式需要填写字幕文案");
            }
            request.setSubtitleMode("custom");
            request.setSubtitle(subtitle);
            return;
        }

        if ("auto".equals(normalizedMode)) {
            if (hasExplicitSubtitleText(subtitle)) {
                request.setSubtitleMode("custom");
                request.setSubtitle(subtitle);
            } else {
                request.setSubtitleMode("auto");
                request.setSubtitle(SUBTITLE_MODE_AUTO);
            }
            return;
        }

        if (hasExplicitSubtitleText(subtitle)) {
            request.setSubtitleMode("custom");
            request.setSubtitle(subtitle);
        } else if (isAutoSubtitle(subtitle) || "auto".equalsIgnoreCase(trimToDefault(subtitle, ""))) {
            request.setSubtitleMode("auto");
            request.setSubtitle(SUBTITLE_MODE_AUTO);
        } else {
            request.setSubtitleMode("off");
            request.setSubtitle(SUBTITLE_MODE_NONE);
        }
    }

    private boolean isSubtitleDisabled(CarSalesVideoDTO request) {
        if (request == null) {
            return true;
        }
        String mode = trimToNull(request.getSubtitleMode());
        String subtitle = normalizeSubtitle(request.getSubtitle());
        return "off".equalsIgnoreCase(trimToDefault(mode, ""))
                || isNoSubtitle(subtitle);
    }

    private boolean hasExplicitSubtitleText(CarSalesVideoDTO request) {
        return request != null && hasExplicitSubtitleText(normalizeSubtitle(request.getSubtitle()));
    }

    private boolean hasExplicitSubtitleText(String subtitle) {
        return StringUtils.hasText(subtitle)
                && !isNoSubtitle(subtitle)
                && !isAutoSubtitle(subtitle)
                && !"auto".equalsIgnoreCase(subtitle);
    }

    private boolean isAutoSubtitleRequested(CarSalesVideoDTO request) {
        if (isSubtitleDisabled(request) || hasExplicitSubtitleText(request)) {
            return false;
        }
        String subtitle = normalizeSubtitle(request == null ? null : request.getSubtitle());
        return isPostAutoSubtitleMode(request)
                || isAutoSubtitle(subtitle)
                || "auto".equalsIgnoreCase(trimToDefault(subtitle, ""));
    }

    private String quotePromptText(String text) {
        String clean = trimToNull(text);
        if (!StringUtils.hasText(clean)) {
            return null;
        }
        return "\"" + clean.replace("\"", "'") + "\"";
    }

    private String nativeVoiceStyleLabel(String style) {
        return nativeVoiceStyleLabel(style, true);
    }

    private String nativeVoiceStyleLabel(String style, boolean hostEnabled) {
        return nativeVoiceStyleLabel(style, hostEnabled, "zh-CN");
    }

    private String nativeVoiceStyleLabel(String style, boolean hostEnabled, String language) {
        if (isEnglishLanguage(language)) {
            return nativeEnglishVoiceStyleLabel(style, hostEnabled);
        }
        String value = normalizeNativeVoiceStyle(style);
        String label = switch (value) {
            case "female_natural_explain" -> "同一位女性汽车销售顾问声音，普通话清晰自然，亲和可信，像销售顾问正常介绍";
            case "male_natural_explain" -> "同一位男性汽车销售顾问声音，普通话清晰自然，稳健可信，像销售顾问正常介绍";
            case "female_clear" -> "同一位青年女性销售声音，普通话，清亮干净、亲和不尖锐，适合短视频口播";
            case "male_clear" -> "同一位青年男性销售声音，普通话，清朗干净、亲和不油腻，适合短视频口播";
            case "female_steady" -> "同一位成年女性汽车顾问声音，普通话，中低音、沉稳可信，像资深销售讲车";
            case "male_steady" -> "同一位成年男性汽车顾问声音，普通话，低中音、稳重可信，像资深销售讲车";
            case "female_live" -> "同一位女性门店主播声音，普通话，节奏轻快，语尾有亲和互动感";
            case "male_live" -> "同一位男性门店主播声音，普通话，节奏轻快，互动感强但不过度喊叫，适合门店短视频";
            case "female_energetic_promo" -> "同一位女性促销型销售声音，普通话，能量更强，重读优惠、权益和到店转化";
            case "male_energetic_promo" -> "同一位男性促销型销售声音，普通话，能量更强，重读优惠、权益和到店转化";
            case "female_review" -> "同一位女性专业评测旁白声音，普通话，理性克制，媒体测评感，卖点表达清楚";
            case "male_review" -> "同一位男性专业评测旁白声音，普通话，理性克制，媒体测评感，卖点表达清楚";
            case "female_luxury_calm" -> "同一位成熟女性高级感旁白声音，普通话，沉稳低饱和，突出品质和配置";
            case "male_luxury_calm" -> "同一位成熟男性高级感旁白声音，普通话，沉稳低饱和，突出品质和配置";
            case "female_young_tech" -> "同一位年轻女性科技感旁白声音，普通话，清爽利落，突出智能座舱、配置和新鲜感";
            case "male_young_tech" -> "同一位年轻男性科技感旁白声音，普通话，清爽利落，突出智能座舱、配置和新鲜感";
            case "female_family_warm" -> "同一位女性温和生活化销售声音，普通话，亲和轻松，突出舒适、空间和家庭场景";
            case "male_family_warm" -> "同一位男性温和生活化销售声音，普通话，亲和轻松，突出舒适、空间和家庭场景";
            case "female_soft_story" -> "同一位女性温柔叙事旁白声音，普通话，声线柔和，节奏有画面感，适合生活方式广告";
            case "male_soft_story" -> "同一位男性温柔叙事旁白声音，普通话，声线柔和，节奏有画面感，适合生活方式广告";
            case "female_local_friendly" -> "同一位女性本地亲和销售声音，自然普通话，可轻微本地口吻但不要使用方言，真实接地气";
            case "male_local_friendly" -> "同一位男性本地亲和销售声音，自然普通话，可轻微本地口吻但不要使用方言，真实接地气";
            default -> "同一位女性汽车销售顾问声音，普通话清晰自然，亲和可信，像销售顾问正常介绍";
        };
        return hostEnabled ? label : label + "；仅作为旁白口吻，画面不出现人物";
    }

    private String nativeEnglishVoiceStyleLabel(String style, boolean hostEnabled) {
        String value = normalizeNativeVoiceStyle(style);
        String label = switch (value) {
            case "female_natural_explain" -> "the same female car sales consultant voice in natural English, clear, friendly and trustworthy";
            case "male_natural_explain" -> "the same male car sales consultant voice in natural English, clear, steady and trustworthy";
            case "female_clear" -> "the same young female car sales narrator, clear and friendly English voice, polished but not pushy";
            case "male_clear" -> "the same young male car sales narrator, bright and friendly English voice, polished but not pushy";
            case "female_steady" -> "the same adult female car consultant voice in English, steady, trustworthy and professional";
            case "male_steady" -> "the same adult male car consultant voice in English, steady, trustworthy and professional";
            case "female_live" -> "the same female showroom host voice in English, upbeat and conversational with light live-selling energy";
            case "male_live" -> "the same male showroom host voice in English, upbeat, engaging and direct without shouting";
            case "female_energetic_promo" -> "the same energetic female promotional narrator in English, emphasizing offers, benefits and conversion cues";
            case "male_energetic_promo" -> "the same energetic male promotional narrator in English, emphasizing offers, benefits and conversion cues";
            case "female_review" -> "the same female professional review narrator in English, rational, calm and clear";
            case "male_review" -> "the same male professional review narrator in English, rational, calm and clear";
            case "female_luxury_calm" -> "the same mature female premium English narrator, calm, low-saturation and high-quality";
            case "male_luxury_calm" -> "the same mature male premium English narrator, calm, low-saturation and high-quality";
            case "female_young_tech" -> "the same young female tech-style English narrator, crisp and concise for smart features";
            case "male_young_tech" -> "the same young male tech-style English narrator, crisp and concise for smart features";
            case "female_family_warm" -> "the same warm female lifestyle English narrator, friendly and relaxed for family-use scenes";
            case "male_family_warm" -> "the same warm male lifestyle English narrator, friendly and relaxed for family-use scenes";
            case "female_soft_story" -> "the same soft female storytelling English narrator, gentle and cinematic";
            case "male_soft_story" -> "the same soft male storytelling English narrator, gentle and cinematic";
            case "female_local_friendly" -> "the same friendly female local-style English narrator, natural and approachable, no heavy dialect";
            case "male_local_friendly" -> "the same friendly male local-style English narrator, natural and approachable, no heavy dialect";
            default -> "the same female car sales consultant voice in natural English, clear, friendly and trustworthy";
        };
        return hostEnabled ? label : label + "; voiceover only, no person appears on screen";
    }

    private String nativeSpeechStyleLabel(String style) {
        String value = trimToDefault(style, "natural");
        return switch (value) {
            case "concise" -> "短促利落，信息密度更高";
            case "emotional" -> "情绪递进，先吸引、再卖点、最后引导咨询";
            case "slow_detail" -> "细节讲解，语速略慢，适合配置说明";
            case "fast_hook" -> "开场前两秒更抓人，随后回到清晰稳定的销售表达";
            case "review_steady" -> "评测式节奏，停顿清楚，适合配置、对比和理性说明";
            case "soft_story" -> "故事化节奏，停顿自然，适合生活场景和情绪铺垫";
            default -> "自然语速，按正常口播节奏生成";
        };
    }

    private String nativeEnglishSpeechStyleLabel(String style) {
        String value = trimToDefault(style, "natural");
        return switch (value) {
            case "concise" -> "concise and direct with higher information density";
            case "emotional" -> "emotional progression: hook first, then selling points, then consultation cue";
            case "slow_detail" -> "slightly slower detailed explanation for configuration and feature clarity";
            case "fast_hook" -> "stronger hook in the first two seconds, then return to clear stable sales narration";
            case "review_steady" -> "review-style rhythm with clear pauses for rational feature explanation";
            case "soft_story" -> "storytelling rhythm with natural pauses for lifestyle emotion";
            default -> "natural speaking speed with normal car-sales narration rhythm";
        };
    }

    private String nativeVoiceConsistencyLock(CarSalesVideoDTO request) {
        String language = normalizeNativeVoiceLanguage(request == null ? null : request.getNativeVoiceLanguage());
        String style = normalizeNativeVoiceStyle(request == null ? null : request.getNativeVoiceStyle());
        String speech = trimToDefault(request == null ? null : request.getNativeSpeechStyle(), "natural");
        String host = hostAppearanceEnabled(request) ? "host_on" : "host_off";
        String lock = "VOICE_LOCK_" + language.replace('-', '_') + "_" + style + "_" + speech + "_" + host;
        return lock + "；本任务所有原生口播片段必须视为同一位固定说话人，严格沿用相同声线、性别、年龄感、语速、音高、口音和情绪强度。";
    }

    private String nativeEnglishVoiceConsistencyLock(CarSalesVideoDTO request) {
        String language = normalizeNativeVoiceLanguage(request == null ? null : request.getNativeVoiceLanguage());
        String style = normalizeNativeVoiceStyle(request == null ? null : request.getNativeVoiceStyle());
        String speech = trimToDefault(request == null ? null : request.getNativeSpeechStyle(), "natural");
        String host = hostAppearanceEnabled(request) ? "host_on" : "host_off";
        String lock = "VOICE_LOCK_" + language.replace('-', '_') + "_" + style + "_" + speech + "_" + host;
        return lock + "; all native narration segments must be treated as one fixed speaker with the same timbre, gender impression, age impression, speaking speed, pitch, accent and emotion intensity";
    }

    private String nativeVoiceLanguageLabel(String language) {
        if (isEnglishLanguage(language)) {
            return "英语讲述；本段口播台词已由后端规范为英文，最终口播必须只使用自然英语，禁止出现中文词句";
        }
        return "中文普通话讲述；本段口播台词已由后端规范为中文，最终口播必须只使用中文普通话，可保留车型名等必要英文专名";
    }

    private String nativeVoiceHardRule(CarSalesVideoDTO request) {
        if (isEnglishNarration(request)) {
            return "硬性口播要求：本段双引号内英文台词是唯一内容来源；必须按英文台词朗读，不得再翻译、不得插入中文、不得新增卖点、扩写、纠错、合并或重复其他段落；不得把台词写到画面里，字幕只在成片后烧录。";
        }
        return "硬性口播要求：本段双引号内中文台词是唯一内容来源；必须用中文普通话逐字朗读，不得翻译成英文、不得插入英文句子、不得改写、扩写、纠错、合并或重复其他段落；不得把台词写到画面里，字幕只在成片后烧录。";
    }

    private String normalizeNativeVoiceStyle(String style) {
        String value = trimToDefault(style, DEFAULT_NATIVE_VOICE_STYLE);
        return switch (value) {
            case "female_natural_explain", "male_natural_explain",
                    "female_clear", "male_clear",
                    "female_steady", "male_steady",
                    "female_live", "male_live",
                    "female_energetic_promo", "male_energetic_promo",
                    "female_review", "male_review",
                    "female_luxury_calm", "male_luxury_calm",
                    "female_young_tech", "male_young_tech",
                    "female_family_warm", "male_family_warm",
                    "female_soft_story", "male_soft_story",
                    "female_local_friendly", "male_local_friendly" -> value;
            case "natural_explain" -> DEFAULT_NATIVE_VOICE_STYLE;
            case "live_seller" -> "male_live";
            case "energetic_promo" -> "female_energetic_promo";
            case "luxury_calm" -> "male_luxury_calm";
            case "young_tech" -> "male_young_tech";
            case "family_warm" -> "female_family_warm";
            case "soft_story" -> "female_soft_story";
            case "local_friendly" -> "female_local_friendly";
            default -> DEFAULT_NATIVE_VOICE_STYLE;
        };
    }

    private String normalizeNativeVoiceLanguage(String language) {
        String value = trimToNull(language);
        if (!StringUtils.hasText(value)) {
            return "zh-CN";
        }
        return switch (value.trim()) {
            case "en-US", "zh-CN" -> value.trim();
            default -> "zh-CN";
        };
    }

    private boolean isEnglishNarration(CarSalesVideoDTO request) {
        return request != null && isEnglishLanguage(request.getNativeVoiceLanguage());
    }

    private boolean isEnglishLanguage(String language) {
        return "en-US".equalsIgnoreCase(trimToDefault(language, "zh-CN"));
    }

    private String localizeVoiceTextForNarration(CarSalesVideoDTO request, String text) {
        ensureNoGarbledSpeechText(text, 40000, "传入的口播文案");
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return clean;
        }
        String language = normalizeNativeVoiceLanguage(request == null ? null : request.getNativeVoiceLanguage());
        if (request != null) {
            request.setNativeVoiceLanguage(language);
        }
        String localized = clean;
        if (shouldTranslateNarrationText(clean, language)) {
            localized = translateNarrationText(clean, language);
            log.info("Car sales narration localized. targetLanguage={}, sourceLength={}, localizedLength={}",
                    language, clean.length(), localized == null ? 0 : localized.length());
        } else {
            log.info("Car sales narration already matches target language. targetLanguage={}, sourceLength={}",
                    language, clean.length());
        }
        localized = trimPrompt(cleanNarrationTranslation(localized), 3000);
        ensureNoGarbledSpeechText(localized, 50214, "规范后的口播文案");
        ensureNarrationLanguageMatches(localized, language);
        return localized;
    }

    private boolean shouldTranslateNarrationText(String text, String language) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        NarrationLanguageStats stats = narrationLanguageStats(text);
        if (isEnglishLanguage(language)) {
            return stats.cjkChars() > 0;
        }
        if (stats.cjkChars() == 0) {
            return stats.latinLetters() >= 4;
        }
        return stats.latinLetters() >= 12 && stats.latinLetters() > stats.cjkChars() * 2;
    }

    private String translateNarrationText(String text, String language) {
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "NARRATION_TRANSLATION_NOT_CONFIGURED: 已选择"
                    + targetNarrationLanguageName(language) + "，但后端未配置文本翻译模型，已停止生成以避免中英混播");
        }
        String prompt = buildNarrationTranslationPrompt(text, language);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", arkTextModel);
            body.put("temperature", 0.1);
            body.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "You are a strict localization engine for short car-sales video narration. "
                                    + "Return only the localized narration text, no explanations."),
                    Map.of("role", "user", "content", prompt)
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(arkBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + arkApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50214, "NARRATION_TRANSLATION_FAILED: "
                        + arkChatErrorMessage(response));
            }
            String translated = extractArkChatMessageContent(response.body());
            if (!StringUtils.hasText(translated)) {
                throw new BusinessException(50214, "NARRATION_TRANSLATION_EMPTY: 文案翻译返回为空");
            }
            return translated.trim();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50214, "NARRATION_TRANSLATION_FAILED: " + exception.getMessage());
        } catch (IOException exception) {
            throw new BusinessException(50214, "NARRATION_TRANSLATION_FAILED: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50214, "NARRATION_TRANSLATION_INTERRUPTED: 文案翻译被中断");
        }
    }

    private String buildNarrationTranslationPrompt(String text, String language) {
        String target = isEnglishLanguage(language) ? "natural spoken English" : "natural spoken Mandarin Chinese";
        String forbidden = isEnglishLanguage(language)
                ? "Do not leave Chinese sentences or Chinese punctuation-only filler in the result. Keep vehicle model names, brand names, prices, numbers and units accurate."
                : "不得保留英文整句；车型名、品牌名、价格、数字和单位可以按行业习惯保留。";
        return """
                Target language: %s.
                Task: translate/localize the narration below into the target language before video generation.
                Rules:
                1. Use only the source narration as the content basis; do not add, delete or invent selling points.
                2. Preserve meaning, numbers, brand/model names and call-to-action.
                3. Preserve paragraph/line order; do not add bullets, numbering, labels or explanations.
                4. Make it concise and speakable for car-sales narration. Correct obvious ASR/OCR transcription noise only when the intended meaning is clear.
                5. Return only the final narration text.
                6. %s

                Narration:
                %s
                """.formatted(target, forbidden, text);
    }

    private void ensureNarrationLanguageMatches(String text, String language) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        NarrationLanguageStats stats = narrationLanguageStats(text);
        if (isEnglishLanguage(language)) {
            if (stats.cjkChars() > 0) {
                throw new BusinessException(50214, "NARRATION_LANGUAGE_MISMATCH: 已选择英语讲述，但规范后的口播仍包含中文，已停止生成以避免中英混播");
            }
            return;
        }
        if (stats.cjkChars() == 0 && stats.latinLetters() >= 8) {
            throw new BusinessException(50214, "NARRATION_LANGUAGE_MISMATCH: 已选择中文讲述，但规范后的口播仍主要是英文，已停止生成以避免中英混播");
        }
        if (stats.latinLetters() >= 24 && stats.latinLetters() > stats.cjkChars() * 3) {
            throw new BusinessException(50214, "NARRATION_LANGUAGE_MISMATCH: 已选择中文讲述，但规范后的口播英文占比过高，已停止生成以避免模型混播");
        }
    }

    private String cleanNarrationTranslation(String value) {
        String text = cleanSpeechText(value);
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("“") && cleaned.endsWith("”"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replaceFirst("(?is)^(final narration|localized narration|translated narration|narration|translation|译文|翻译结果|口播文案)\\s*[:：]\\s*", "").trim();
        return cleaned;
    }

    private boolean containsCjk(String text) {
        return narrationLanguageStats(text).cjkChars() > 0;
    }

    private int countLatinLetters(String text) {
        return narrationLanguageStats(text).latinLetters();
    }

    private NarrationLanguageStats narrationLanguageStats(String text) {
        if (!StringUtils.hasText(text)) {
            return new NarrationLanguageStats(0, 0);
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) {
                cjk++;
            } else if ((codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z')) {
                latin++;
            }
        }
        return new NarrationLanguageStats(cjk, latin);
    }

    private record NarrationLanguageStats(int cjkChars, int latinLetters) {
    }

    private String targetNarrationLanguageName(String language) {
        return isEnglishLanguage(language) ? "英语讲述" : "中文讲述";
    }

    private String extractArkChatMessageContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return firstNonBlank(
                    textAtPointer(root, "/choices/0/message/content"),
                    textAtPointer(root, "/choices/0/text")
            );
        } catch (IOException exception) {
            throw new BusinessException(50214, "NARRATION_TRANSLATION_INVALID_RESPONSE: " + exception.getMessage());
        }
    }

    private String textAtPointer(JsonNode root, String pointer) {
        if (root == null || !StringUtils.hasText(pointer)) {
            return null;
        }
        JsonNode node = root.at(pointer);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private String arkChatErrorMessage(HttpResponse<String> response) {
        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return "http=" + response.statusCode() + ", empty response body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = firstNonBlank(
                    textAtPointer(root, "/error/message"),
                    textAtPointer(root, "/message"),
                    trimPrompt(body, 500)
            );
            return "http=" + response.statusCode() + ", message=" + message;
        } catch (IOException exception) {
            return "http=" + response.statusCode() + ", body=" + trimPrompt(body, 500);
        }
    }

    private String trimPrompt(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), maxLength));
        return value.substring(0, end);
    }

    private int normalizeSegmentCount(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(12, value));
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

    private void normalizeCarSalesTextInputs(CarSalesVideoDTO request) {
        ensureNoGarbledSpeechText(request.getSubtitle(), 40000, "字幕文案");
        ensureNoGarbledSpeechText(request.getFinalVoiceText(), 40000, "口播文案");
        request.setSubtitle(cleanSpeechText(request.getSubtitle()));
        request.setFinalVoiceText(cleanSpeechText(request.getFinalVoiceText()));
        normalizeSubtitleRequest(request);
        CarSalesVideoDTO.TextOverlay subtitleOverlay = request.getSubtitleOverlay();
        if (subtitleOverlay != null) {
            ensureNoGarbledSpeechText(subtitleOverlay.getText(), 40000, "字幕样式文案");
            subtitleOverlay.setText(cleanSpeechText(subtitleOverlay.getText()));
        }
        CarSalesVideoDTO.TextOverlay overlay = request.getHeadlineOverlay();
        if (overlay != null) {
            ensureNoGarbledSpeechText(overlay.getText(), 40000, "标题文案");
            overlay.setText(cleanSpeechText(overlay.getText()));
        }
        if (request.getScenes() == null) {
            return;
        }
        for (CarSalesVideoDTO.Scene scene : request.getScenes()) {
            if (scene != null) {
                ensureNoGarbledSpeechText(scene.getVoiceText(), 40000, "分镜口播文案");
                scene.setVoiceText(cleanSpeechText(scene.getVoiceText()));
            }
        }
    }

    private void normalizeCarSalesVoicePolicy(CarSalesVideoDTO request) {
        if (request == null) {
            throw new BusinessException(40000, "请求体不能为空");
        }
        normalizeCarSalesTextInputs(request);
        String rawMode = trimToNull(request.getAudioMode());
        String rawVoicePolicy = trimToNull(request.getVoicePolicy());
        String audioUrl = trimToNull(request.getAudioUrl());
        String generatedVoiceUrl = trimToNull(request.getGeneratedVoiceUrl());
        request.setNativeVoiceLanguage(normalizeNativeVoiceLanguage(request.getNativeVoiceLanguage()));
        request.setNativeVoiceStyle(normalizeNativeVoiceStyle(request.getNativeVoiceStyle()));
        if (isImplicitDefaultVoiceoverRequest(request, rawMode, rawVoicePolicy, audioUrl, generatedVoiceUrl)) {
            clearImplicitDefaultVoiceover(request);
            rawMode = AUDIO_MODE_NONE;
            rawVoicePolicy = AUDIO_MODE_NONE;
            audioUrl = null;
            generatedVoiceUrl = null;
        }
        String mode = rawMode == null
                ? (StringUtils.hasText(audioUrl) || StringUtils.hasText(generatedVoiceUrl)
                ? AUDIO_MODE_POST_MIX
                : "auto_tts".equalsIgnoreCase(rawVoicePolicy)
                ? AUDIO_MODE_AUTO_TTS
                : AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(rawVoicePolicy)
                ? AUDIO_MODE_MODEL_NATIVE
                : AUDIO_MODE_NONE)
                : trimToDefault(rawMode, AUDIO_MODE_NONE);
        if (AUDIO_MODE_NONE.equalsIgnoreCase(mode) && "auto_tts".equalsIgnoreCase(rawVoicePolicy)) {
            mode = AUDIO_MODE_AUTO_TTS;
        }
        if (AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(mode)) {
            if (StringUtils.hasText(generatedVoiceUrl)) {
                request.setAudioUrl(generatedVoiceUrl);
                request.setAudioMode(AUDIO_MODE_POST_MIX);
            } else if (StringUtils.hasText(audioUrl)) {
                request.setAudioMode(AUDIO_MODE_POST_MIX);
            } else {
                request.setAudioUrl(null);
                request.setAudioMode(AUDIO_MODE_AUTO_TTS);
            }
            request.setVoicePolicy("auto_tts");
            return;
        }
        if (AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(mode)) {
            request.setAudioUrl(null);
            request.setAudioMode(AUDIO_MODE_MODEL_NATIVE);
            request.setVoicePolicy("model_native");
            return;
        }
        if (AUDIO_MODE_NONE.equalsIgnoreCase(mode)) {
            request.setAudioUrl(null);
            request.setAudioMode(AUDIO_MODE_NONE);
            if (!StringUtils.hasText(request.getVoicePolicy())) {
                request.setVoicePolicy("none");
            }
            return;
        }
        if (!AUDIO_MODE_NONE.equalsIgnoreCase(mode)
                && !AUDIO_MODE_POST_MIX.equalsIgnoreCase(mode)
                && !AUDIO_MODE_REFERENCE.equalsIgnoreCase(mode)
                && !AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(mode)) {
            throw new BusinessException(40000, "不支持的 audioMode: " + mode);
        }
        if (!AUDIO_MODE_NONE.equalsIgnoreCase(mode) && !StringUtils.hasText(audioUrl)) {
            throw new BusinessException(40000, "选择口播音频模式时必须提供 audioUrl");
        }
        if (StringUtils.hasText(audioUrl) && StringUtils.hasText(request.getBgmUrl())
                && audioUrl.equals(trimToNull(request.getBgmUrl()))) {
            throw new BusinessException(40000, "BGM 不能作为口播音频来源");
        }
        request.setAudioMode(StringUtils.hasText(audioUrl) ? mode : AUDIO_MODE_NONE);
        if (!StringUtils.hasText(request.getVoicePolicy())) {
            request.setVoicePolicy(StringUtils.hasText(audioUrl) ? "user_audio" : "none");
        }
    }

    private boolean isImplicitDefaultVoiceoverRequest(CarSalesVideoDTO request, String rawMode,
                                                      String rawVoicePolicy, String audioUrl,
                                                      String generatedVoiceUrl) {
        if (request == null
                || StringUtils.hasText(audioUrl)
                || StringUtils.hasText(generatedVoiceUrl)
                || request.getGeneratedVoiceAssetId() != null
                || Boolean.TRUE.equals(request.getStrictVoiceText())) {
            return false;
        }
        if (!"auto".equalsIgnoreCase(trimToDefault(request.getVoiceTextSource(), ""))) {
            return false;
        }
        return AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(rawMode)
                || AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(rawMode)
                || AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(rawVoicePolicy)
                || AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(rawVoicePolicy);
    }

    private void clearImplicitDefaultVoiceover(CarSalesVideoDTO request) {
        request.setAudioUrl(null);
        request.setGeneratedVoiceUrl(null);
        request.setGeneratedVoiceAssetId(null);
        request.setAudioMode(AUDIO_MODE_NONE);
        request.setVoicePolicy("none");
        request.setFinalVoiceText(null);
        request.setStrictVoiceText(null);
        if (request.getScenes() == null) {
            return;
        }
        for (CarSalesVideoDTO.Scene scene : request.getScenes()) {
            if (scene != null) {
                scene.setVoiceText(null);
            }
        }
    }

    private void prepareModelNativeVoiceover(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (!shouldGenerateNativeAudio(request)) {
            return;
        }
        boolean strictVoiceText = isStrictVoiceText(request);
        String rawFinalVoiceText = strictVoiceText
                ? trimToNull(request.getFinalVoiceText())
                : resolveFinalVoiceText(request, scenes);
        String finalVoiceText = localizeVoiceTextForNarration(request, rawFinalVoiceText);
        if (!StringUtils.hasText(finalVoiceText)) {
            throw new BusinessException(40000, "MODEL_NATIVE_TEXT_REQUIRED: 文案生成音视频需要口播文案或可整理文案的车辆信息");
        }
        request.setFinalVoiceText(finalVoiceText);
        request.setVoicePolicy("model_native");
        applyVoiceTextToScenes(scenes, finalVoiceText);
    }

    private void enforceStrictVoiceConsistency(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (!shouldGenerateNativeAudio(request) || scenes == null || scenes.size() <= 1) {
            return;
        }
        log.info("Car sales model-native audio preserved by explicit voiceover mode. scenes={} hostAppearanceEnabled={}",
                scenes.size(), hostAppearanceEnabled(request));
    }

    private void prepareAutoTtsVoiceover(TaskItem task, CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes,
                                         String model) {
        if (request == null || !AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE))) {
            return;
        }
        String finalVoiceText = resolveFinalVoiceText(request, scenes);
        if (!StringUtils.hasText(finalVoiceText)) {
            throw new BusinessException(40000, "AUTO_TTS_TEXT_REQUIRED: 未选择口播音频时，需要口播文案或可生成文案的车辆/分镜信息");
        }
        finalVoiceText = localizeVoiceTextForNarration(request, finalVoiceText);
        request.setFinalVoiceText(finalVoiceText);
        applyVoiceTextToScenes(scenes, finalVoiceText);
        double targetDurationSeconds = resolveTargetVisualDurationSeconds(scenes, request, model);
        CarSalesAutoTtsService.AutoTtsResult result;
        try {
            result = carSalesAutoTtsService.synthesize(task, request, finalVoiceText, targetDurationSeconds);
        } catch (BusinessException ex) {
            if (isAutoTtsQuotaLimitException(ex)) {
                if (scenes != null && scenes.size() > 1) {
                    throw new BusinessException(40000,
                            "AUTO_TTS_QUOTA_LIMIT: 后期旁白配音额度不足。多段成片不会自动改用模型原生口播，请上传统一口播音频，或补充旁白配音额度后再生成。");
                }
                fallbackAutoTtsToModelNativeVoiceover(request, scenes, ex);
                return;
            }
            throw ex;
        }
        request.setGeneratedVoiceAssetId(result.assetId());
        request.setGeneratedVoiceUrl(result.audioUrl());
        request.setAudioUrl(result.audioUrl());
        request.setAudioMode(AUDIO_MODE_POST_MIX);
        request.setVoicePolicy("auto_tts");
        if (SYNC_STRATEGY_AUTO.equals(normalizeSyncStrategy(request))) {
            request.setSyncStrategy(SYNC_STRATEGY_AUDIO_MASTER);
        }
        appendGeneratedVoiceBinding(request, result);
    }

    private void fallbackAutoTtsToModelNativeVoiceover(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes,
                                                       BusinessException cause) {
        log.warn("Car sales automatic narration quota exceeded, fallback to model-native voiceover. message={}",
                cause == null ? null : cause.getMessage());
        request.setAudioUrl(null);
        request.setGeneratedVoiceUrl(null);
        request.setGeneratedVoiceAssetId(null);
        request.setAudioMode(AUDIO_MODE_MODEL_NATIVE);
        request.setVoicePolicy("model_native");
        if (SYNC_STRATEGY_AUDIO_MASTER.equals(normalizeSyncStrategy(request))) {
            request.setSyncStrategy(SYNC_STRATEGY_AUTO);
        }
        prepareModelNativeVoiceover(request, scenes);
    }

    private boolean isAutoTtsQuotaLimitException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("voiceover_quota_exceeded")
                        || normalized.contains("quota exceeded")
                        || normalized.contains("text_words_lifetime")
                        || normalized.contains("quota_exceeded")
                        || normalized.contains("额度")
                        || normalized.contains("用量")
                        || normalized.contains("超限")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private double resolveTargetVisualDurationSeconds(List<CarSalesVideoDTO.Scene> scenes,
                                                      CarSalesVideoDTO request,
                                                      String model) {
        if (scenes != null && !scenes.isEmpty()) {
            return scenes.stream()
                    .filter(scene -> scene != null)
                    .mapToDouble(scene -> normalizeSegmentDuration(scene.getDuration(), model))
                    .sum();
        }
        int count = normalizeSegmentCount(request == null ? null : request.getSegmentCount());
        int duration = normalizeSegmentDuration(request == null ? null : request.getSegmentDuration(), model);
        return Math.max(1, count * duration);
    }

    private String resolveFinalVoiceText(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (isStrictVoiceText(request)) {
            return trimPrompt(collapseRepeatedVoiceLines(request.getFinalVoiceText()), 3000);
        }
        String explicit = trimToNull(request.getFinalVoiceText());
        if (StringUtils.hasText(explicit)) {
            return trimPrompt(collapseRepeatedVoiceLines(explicit), 3000);
        }

        List<String> sceneLines = new ArrayList<>();
        if (scenes != null) {
            for (CarSalesVideoDTO.Scene scene : scenes) {
                if (scene != null && StringUtils.hasText(scene.getVoiceText())) {
                    sceneLines.add(scene.getVoiceText().trim());
                }
            }
        }
        if (!sceneLines.isEmpty()) {
            return trimPrompt(collapseRepeatedVoiceLines(String.join("\n", sceneLines)), 3000);
        }

        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(request.getBrandModel())) {
            parts.add("今天带大家看 " + request.getBrandModel().trim());
        } else {
            parts.add("今天带大家看这台车");
        }
        if (StringUtils.hasText(request.getAudience())) {
            parts.add("它很适合" + request.getAudience().trim());
        }
        if (StringUtils.hasText(request.getSellingPoints())) {
            parts.add("核心亮点包括" + request.getSellingPoints().trim());
        }
        if (StringUtils.hasText(request.getPrompt())) {
            parts.add("画面风格上会突出" + request.getPrompt().trim());
        }
        if (StringUtils.hasText(request.getCallToAction())) {
            parts.add(request.getCallToAction().trim());
        }
        return trimPrompt(String.join("。", parts) + "。", 3000);
    }

    private boolean isStrictVoiceText(CarSalesVideoDTO request) {
        return request != null && Boolean.TRUE.equals(request.getStrictVoiceText());
    }

    private void applyVoiceTextToScenes(List<CarSalesVideoDTO.Scene> scenes, String finalVoiceText) {
        if (scenes == null || scenes.isEmpty() || !StringUtils.hasText(finalVoiceText)) {
            return;
        }
        List<String> chunks = splitVoiceTextForSegments(finalVoiceText, scenes.size());
        for (int i = 0; i < scenes.size(); i++) {
            CarSalesVideoDTO.Scene scene = scenes.get(i);
            if (scene == null) {
                continue;
            }
            String chunk = i < chunks.size() ? chunks.get(i) : null;
            if (StringUtils.hasText(chunk)) {
                scene.setVoiceText(trimPrompt(chunk, 600));
            } else {
                scene.setVoiceText(null);
            }
        }
    }

    private List<String> splitVoiceTextForSegments(String text, int total) {
        String clean = collapseRepeatedVoiceLines(text);
        if (!StringUtils.hasText(clean)) {
            return List.of();
        }
        int count = Math.max(1, total);
        if (count <= 1) {
            return List.of(clean);
        }
        List<String> clauses = new ArrayList<>();
        for (String part : clean.split("(?<=[。！？!?；;，,、\\.])|\\R+")) {
            if (StringUtils.hasText(part)) {
                clauses.add(part.trim());
            }
        }
        if (clauses.size() <= 1) {
            return shouldKeepSpeechUnitWhole(clean) ? List.of(clean) : fitVoiceChunksToCount(splitTextByLength(clean, count), count);
        }
        int totalLength = clauses.stream().mapToInt(String::length).sum();
        int targetLength = Math.max(1, (int) Math.ceil(totalLength / (double) count));
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i);
            int remainingClauses = clauses.size() - i;
            int remainingSlots = count - chunks.size() - 1;
            boolean shouldClose = !current.isEmpty()
                    && current.length() + clause.length() > targetLength
                    && remainingSlots > 0
                    && remainingClauses >= remainingSlots;
            if (shouldClose) {
                chunks.add(current.toString());
                current = new StringBuilder(clause);
            } else {
                appendVoiceClause(current, clause);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return fitVoiceChunksToCount(chunks, count);
    }

    private String collapseRepeatedVoiceLines(String text) {
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return null;
        }
        String[] lines = clean.split("\\R+");
        if (lines.length <= 1) {
            return clean;
        }
        List<String> collapsed = new ArrayList<>();
        String previousKey = null;
        for (String line : lines) {
            String item = cleanSpeechText(line);
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String key = voiceLineKey(item);
            if (key.equals(previousKey)) {
                continue;
            }
            collapsed.add(item);
            previousKey = key;
        }
        return collapsed.isEmpty() ? clean : String.join("\n", collapsed);
    }

    private String voiceLineKey(String text) {
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return "";
        }
        return clean.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private List<String> fitVoiceChunksToCount(List<String> chunks, int total) {
        int count = Math.max(1, total);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> clean = chunks.stream()
                .map(this::cleanSpeechText)
                .filter(StringUtils::hasText)
                .toList();
        if (clean.size() <= count) {
            return new ArrayList<>(clean);
        }
        List<String> fitted = new ArrayList<>(clean.subList(0, count - 1));
        StringBuilder tail = new StringBuilder();
        for (String chunk : clean.subList(count - 1, clean.size())) {
            appendVoiceClause(tail, chunk);
        }
        if (!tail.isEmpty()) {
            fitted.add(tail.toString());
        }
        return fitted;
    }

    private boolean shouldKeepSpeechUnitWhole(String text) {
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return false;
        }
        int words = countLatinWords(clean);
        if (words >= 4) {
            return words <= 24 && clean.length() <= 180;
        }
        return clean.length() <= 72;
    }

    private int countLatinWords(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        Matcher matcher = Pattern.compile("[A-Za-z0-9'_+-]+").matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private List<String> splitTextByLength(String text, int total) {
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return List.of();
        }
        int count = Math.max(1, total);
        if (count <= 1) {
            return List.of(clean);
        }
        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        while (chunks.size() < count - 1 && cursor < clean.length()) {
            int remainingSlots = count - chunks.size();
            int remainingLength = clean.length() - cursor;
            int preferredEnd = cursor + (int) Math.ceil(remainingLength / (double) remainingSlots);
            int end = Math.max(cursor + 1, smartVoiceSplitBoundary(clean, cursor, preferredEnd));
            String chunk = clean.substring(cursor, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            cursor = skipVoiceWhitespace(clean, end);
        }
        String tail = clean.substring(Math.min(cursor, clean.length())).trim();
        if (StringUtils.hasText(tail)) {
            chunks.add(tail);
        }
        return chunks;
    }

    private void appendVoiceClause(StringBuilder current, String clause) {
        if (!StringUtils.hasText(clause)) {
            return;
        }
        if (current.isEmpty()) {
            current.append(clause);
            return;
        }
        char last = current.charAt(current.length() - 1);
        char first = clause.charAt(0);
        char beforeLast = current.length() >= 2 ? current.charAt(current.length() - 2) : '\0';
        if (shouldInsertSpeechSpace(last, beforeLast, first)) {
            current.append(' ');
        }
        current.append(clause);
    }

    private int smartVoiceSplitBoundary(String text, int start, int preferredEnd) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int minEnd = Math.min(text.length(), start + 1);
        int clamped = Math.max(minEnd, Math.min(text.length(), preferredEnd));
        if (clamped >= text.length()) {
            return text.length();
        }
        int window = 28;
        int leftLimit = Math.max(start + 1, clamped - window);
        int rightLimit = Math.min(text.length() - 1, clamped + window);
        for (int i = clamped; i >= leftLimit; i--) {
            if (isPreferredVoiceBreak(text.charAt(i - 1)) && isSafeSpeechBoundary(text, i)) {
                return skipVoiceWhitespace(text, i);
            }
        }
        for (int i = clamped; i <= rightLimit; i++) {
            if (isPreferredVoiceBreak(text.charAt(i - 1)) && isSafeSpeechBoundary(text, i)) {
                return skipVoiceWhitespace(text, i);
            }
        }
        if (clamped > 0 && clamped < text.length()
                && isAsciiWordChar(text.charAt(clamped - 1))
                && isAsciiWordChar(text.charAt(clamped))) {
            for (int i = clamped; i >= leftLimit; i--) {
                if (!isAsciiWordChar(text.charAt(i - 1))) {
                    return skipVoiceWhitespace(text, i);
                }
            }
            for (int i = clamped; i <= rightLimit; i++) {
                if (!isAsciiWordChar(text.charAt(i))) {
                    return skipVoiceWhitespace(text, i + 1);
                }
            }
            for (int i = rightLimit + 1; i < text.length(); i++) {
                if (!isAsciiWordChar(text.charAt(i))) {
                    return skipVoiceWhitespace(text, i + 1);
                }
            }
            return text.length();
        }
        return clamped;
    }

    private int skipVoiceWhitespace(String text, int index) {
        int next = Math.max(0, Math.min(text.length(), index));
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
            next++;
        }
        return next;
    }

    private boolean isPreferredVoiceBreak(char ch) {
        return Character.isWhitespace(ch)
                || ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '，' || ch == '、'
                || ch == '!' || ch == '?' || ch == ';' || ch == ',' || ch == '.';
    }

    private boolean isAsciiWordChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '\'' || ch == '_' || ch == '+' || ch == '-';
    }

    private boolean isSafeSpeechBoundary(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return true;
        }
        char prev = text.charAt(index - 1);
        char next = text.charAt(index);
        char beforePrev = index >= 2 ? text.charAt(index - 2) : '\0';
        if (isAsciiWordChar(prev) && isAsciiWordChar(next)) {
            return false;
        }
        return !((prev == '.' || prev == ',') && Character.isDigit(beforePrev) && Character.isDigit(next));
    }

    private boolean shouldInsertSpeechSpace(char last, char beforeLast, char first) {
        if (isAsciiWordChar(last) && isAsciiWordChar(first)) {
            return true;
        }
        if ((last == '.' || last == ',') && Character.isDigit(beforeLast) && Character.isDigit(first)) {
            return false;
        }
        return "。！？!?；;，,、.:：".indexOf(last) >= 0 && isAsciiWordChar(first);
    }

    private void appendGeneratedVoiceBinding(CarSalesVideoDTO request, CarSalesAutoTtsService.AutoTtsResult result) {
        if (request == null || result == null || !StringUtils.hasText(result.audioUrl())) {
            return;
        }
        List<CarSalesVideoDTO.AssetRoleBinding> bindings = request.getAssetRoleBindings() == null
                ? new ArrayList<>()
                : new ArrayList<>(request.getAssetRoleBindings());
        boolean exists = bindings.stream()
                .anyMatch(item -> item != null && StringUtils.hasText(item.getUrl())
                        && result.audioUrl().equals(item.getUrl().trim()));
        if (!exists) {
            CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
            binding.setAssetId(result.assetId());
            binding.setUrl(result.audioUrl());
            binding.setAssetType("AUDIO");
            binding.setAssetRole("voiceover");
            binding.setLabel("系统自动 TTS 口播");
            bindings.add(binding);
            request.setAssetRoleBindings(bindings);
        }
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

    private String normalizeSyncStrategy(CarSalesVideoDTO request) {
        String strategy = trimToDefault(request == null ? null : request.getSyncStrategy(), SYNC_STRATEGY_AUTO)
                .toLowerCase(Locale.ROOT);
        return switch (strategy) {
            case SYNC_STRATEGY_AUDIO_MASTER, SYNC_STRATEGY_VISUAL_MASTER -> strategy;
            default -> SYNC_STRATEGY_AUTO;
        };
    }

    private boolean shouldUseAudioMasterSync(CarSalesVideoDTO request, String audioUrl) {
        String strategy = normalizeSyncStrategy(request);
        if (SYNC_STRATEGY_AUDIO_MASTER.equals(strategy)) {
            return StringUtils.hasText(audioUrl);
        }
        if (SYNC_STRATEGY_VISUAL_MASTER.equals(strategy)) {
            return false;
        }
        if (isUserVoiceAudioPolicy(request) || isAutoTtsVoicePolicy(request)) {
            return StringUtils.hasText(audioUrl);
        }
        return false;
    }

    private boolean isUserVoiceAudioPolicy(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        String policy = trimToDefault(request.getVoicePolicy(), "");
        String mode = trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE);
        return "user_audio".equalsIgnoreCase(policy)
                || (StringUtils.hasText(request.getAudioUrl())
                && AUDIO_MODE_POST_MIX.equalsIgnoreCase(mode));
    }

    private boolean isAutoTtsVoicePolicy(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        return "auto_tts".equalsIgnoreCase(trimToDefault(request.getVoicePolicy(), ""))
                || AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE));
    }

    private boolean shouldGenerateNativeAudio(CarSalesVideoDTO request) {
        return request != null
                && !StringUtils.hasText(request.getAudioUrl())
                && AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE));
    }

    private String voiceConsistencyMode(CarSalesVideoDTO request) {
        if (request == null) {
            return "unknown";
        }
        String mode = trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE);
        String policy = trimToDefault(request.getVoicePolicy(), "none");
        if ("auto_tts".equalsIgnoreCase(policy) || AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(mode)) {
            return "single_tts_post_mix";
        }
        if (shouldUseFinalAudio(request)) {
            return "single_user_audio_post_mix";
        }
        if (shouldGenerateNativeAudio(request)) {
            return "model_native_voice_lock";
        }
        return AUDIO_MODE_NONE.equalsIgnoreCase(mode) ? "none" : mode;
    }

    private boolean hostAppearanceEnabled(CarSalesVideoDTO request) {
        return request != null && Boolean.TRUE.equals(request.getHostAppearanceEnabled());
    }

    private String trimToDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        if (!StringUtils.hasText(trimmed)) {
            return fallback;
        }
        return AUDIO_MODE_NONE.equalsIgnoreCase(trimmed) ? AUDIO_MODE_NONE : trimmed;
    }

    private String normalizeSeedanceRatio(String value) {
        String trimmed = trimToNull(value);
        if (!StringUtils.hasText(trimmed) || "auto".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String cleanSpeechText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == 0xFFFD || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                continue;
            }
            if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t') {
                continue;
            }
            builder.appendCodePoint(codePoint);
        }
        String cleaned = builder.toString()
                .replaceAll("[ \\t]+", " ")
                .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }

    private void ensureNoGarbledSpeechText(String value, int errorCode, String label) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (containsMissingGlyphPlaceholder(value) || looksLikeMojibakeText(value)) {
            throw new BusinessException(errorCode,
                    label + "包含疑似乱码或缺字方框，请清理文案/确认语言后再生成");
        }
    }

    private boolean containsMissingGlyphPlaceholder(String value) {
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == 0xFFFD
                    || codePoint == 0x25A0
                    || codePoint == 0x25A1
                    || (codePoint >= 0x25FB && codePoint <= 0x25FE)
                    || codePoint == 0x2B1A
                    || codePoint == 0x2B1B) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeMojibakeText(String value) {
        String[] markers = {
                "Ã", "Â", "â€", "ä¸", "å", "æ", "ç", "è", "é",
                "锛", "銆", "鐨", "涓", "鍙", "瀛", "枃", "杞", "嗗", "勬"
        };
        int hits = 0;
        for (String marker : markers) {
            int index = value.indexOf(marker);
            while (index >= 0) {
                hits++;
                if (hits >= 4) {
                    return true;
                }
                index = value.indexOf(marker, index + marker.length());
            }
        }
        return false;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Map<String, Object> buildCarSalesSeedanceDiagnostics(TaskItem task, CarSalesVideoDTO request, String model,
                                                                 int index, String finalPrompt,
                                                                 String storyboardVisualPrompt,
                                                                 Set<String> ignoredFields,
                                                                 SceneImageSelection imageSelection,
                                                                 boolean hasReferenceImage,
                                                                 boolean passesAudioUrlToSeedance) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", model);
        meta.put("taskType", task.taskType());
        meta.put("segmentIndex", index);
        meta.put("taskMode", request.getTaskMode());
        meta.put("multiCarCompare", isMultiCarCompareRequest(request));
        meta.put("audioMode", trimToDefault(request.getAudioMode(), AUDIO_MODE_NONE));
        meta.put("voiceConsistencyMode", voiceConsistencyMode(request));
        meta.put("syncStrategy", normalizeSyncStrategy(request));
        meta.put("subtitleTimingMode", normalizeSubtitleTimingMode(request));
        meta.put("hasAudioUrl", StringUtils.hasText(request.getAudioUrl()));
        meta.put("passesAudioUrlToSeedance", passesAudioUrlToSeedance);
        meta.put("generateNativeAudio", shouldGenerateNativeAudio(request));
        meta.put("hasReferenceImage", hasReferenceImage);
        meta.put("hasBgmUrl", StringUtils.hasText(request.getBgmUrl()));
        meta.put("audioUrl", StringUtils.hasText(request.getAudioUrl()) ? request.getAudioUrl().trim() : null);
        meta.put("bgmUrl", StringUtils.hasText(request.getBgmUrl()) ? request.getBgmUrl().trim() : null);
        meta.put("voicePolicy", request.getVoicePolicy());
        meta.put("finalVoiceText", request.getFinalVoiceText());
        meta.put("generatedVoiceAssetId", request.getGeneratedVoiceAssetId());
        meta.put("generatedVoiceUrl", request.getGeneratedVoiceUrl());
        meta.put("autoTtsVoiceId", request.getAutoTtsVoiceId());
        meta.put("autoTtsSpeed", request.getAutoTtsSpeed());
        meta.put("autoTtsVolume", request.getAutoTtsVolume());
        meta.put("autoTtsPitch", request.getAutoTtsPitch());
        meta.put("nativeVoiceLanguage", request.getNativeVoiceLanguage());
        meta.put("nativeVoiceStyle", request.getNativeVoiceStyle());
        meta.put("nativeSpeechStyle", request.getNativeSpeechStyle());
        meta.put("assetRoleBindings", request.getAssetRoleBindings());
        meta.put("carPackages", request.getCarPackages());
        meta.put("hostAppearanceEnabled", hostAppearanceEnabled(request));
        meta.put("selectedReferenceImages", imageSelection == null ? List.of() : imageSelection.imageUrls());
        meta.put("selectedReferenceRoles", imageSelection == null ? List.of() : imageSelection.roles());
        meta.put("selectedReferenceLabels", imageSelection == null ? List.of() : imageSelection.labels());
        meta.put("referenceImageStrategy", imageSelection == null ? null : imageSelection.strategy());
        meta.put("referencePriorityRoles", imageSelection == null ? List.of() : imageSelection.priorityRoles());
        meta.put("materialCompleteness", buildCarMaterialCompleteness(request));
        appendCarSalesTestMetadata(meta, request);
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
        meta.put("taskMode", request.getTaskMode());
        meta.put("scene", scene);
        meta.put("segmentRequest", segmentRequest);
        meta.put("seedanceDiagnostics", diagnostics);
        meta.put("sourceAssetIds", request.getSourceAssetIds());
        meta.put("carPackages", request.getCarPackages());
        meta.put("hostImageUrl", request.getHostImageUrl());
        meta.put("hostAppearanceEnabled", hostAppearanceEnabled(request));
        meta.put("assetRoleBindings", request.getAssetRoleBindings());
        appendCarSalesTestMetadata(meta, request);
        return toJson(meta);
    }

    private void appendCarSalesTestMetadata(Map<String, Object> meta, CarSalesVideoDTO request) {
        if (meta == null || request == null) {
            return;
        }
        meta.put("testBatch", trimToNull(request.getTestBatch()));
        meta.put("sampleId", trimToNull(request.getSampleId()));
        meta.put("inputImageCount", carSalesInputImageCount(request));
        meta.put("sellingPoint", trimToNull(request.getSellingPoints()));
        meta.put("template", trimToNull(request.getSalesTemplate()));
        meta.put("outputPurpose", trimToDefault(request.getOutputPurpose(), "car_sales_golden_path"));
        meta.put("qualityScore", request.getQualityScore());
        meta.put("reviewer", trimToNull(request.getReviewer()));
    }

    private int carSalesInputImageCount(CarSalesVideoDTO request) {
        if (request == null) {
            return 0;
        }
        if (request.getAssetRoleBindings() != null && !request.getAssetRoleBindings().isEmpty()) {
            long count = request.getAssetRoleBindings().stream()
                    .filter(binding -> binding != null && isCarSalesInputImage(binding.getAssetType(), binding.getAssetRole()))
                    .count();
            if (count > 0) {
                return (int) count;
            }
        }
        return request.getCarImageUrls() == null ? 0 : request.getCarImageUrls().size();
    }

    private boolean isCarSalesInputImage(String assetType, String assetRole) {
        String type = assetType == null ? "" : assetType.trim().toLowerCase(Locale.ROOT);
        String role = assetRole == null ? "" : assetRole.trim().toLowerCase(Locale.ROOT);
        return "image".equals(type) || "cover".equals(type) || role.startsWith("car_") || role.startsWith("scene_");
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
            List<Boolean> audioStreams = segmentFiles.stream().map(this::hasAudioStream).toList();
            boolean hasAnyAudio = audioStreams.stream().anyMatch(Boolean::booleanValue);
            boolean hasAllAudio = audioStreams.stream().allMatch(Boolean::booleanValue);
            if (hasAnyAudio && !hasAllAudio) {
                stitchVideoSegmentsWithAudioFallback(segmentFiles, audioStreams, outputFile);
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

    private void stitchVideoSegmentsWithAudioFallback(List<Path> segmentFiles, List<Boolean> audioStreams, Path outputFile) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-stitch-audio-fallback.log");
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        for (Path file : segmentFiles) {
            command.add("-i");
            command.add(file.toString());
        }

        StringBuilder filter = new StringBuilder();
        StringBuilder concatInputs = new StringBuilder();
        for (int i = 0; i < segmentFiles.size(); i++) {
            filter.append('[').append(i).append(":v:0]")
                    .append("setpts=PTS-STARTPTS")
                    .append("[v").append(i).append("];");
            if (Boolean.TRUE.equals(audioStreams.get(i))) {
                filter.append('[').append(i).append(":a:0]")
                        .append("aresample=async=1:first_pts=0,asetpts=PTS-STARTPTS")
                        .append("[a").append(i).append("];");
            } else {
                double duration = firstPositive(probeMediaDurationSeconds(segmentFiles.get(i)), 1.0D);
                filter.append("anullsrc=channel_layout=stereo:sample_rate=44100,")
                        .append("atrim=duration=").append(formatFilterNumber(duration))
                        .append(",asetpts=PTS-STARTPTS")
                        .append("[a").append(i).append("];");
            }
            concatInputs.append("[v").append(i).append("][a").append(i).append(']');
        }
        filter.append(concatInputs)
                .append("concat=n=").append(segmentFiles.size())
                .append(":v=1:a=1[outv][outa]");

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[outv]");
        command.add("-map");
        command.add("[outa]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputFile.toString());
        runMediaCommand(command, logFile, 10, "FFmpeg 拼接超时", "FFmpeg 拼接失败");
    }

    private boolean hasAudioStream(Path mediaFile) {
        if (mediaFile == null || !Files.exists(mediaFile)) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=codec_type",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaFile.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("FFprobe audio stream timeout file={}", mediaFile);
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("audio");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FFprobe audio stream interrupted file={}", mediaFile);
            return false;
        } catch (Exception e) {
            log.warn("FFprobe audio stream failed file={} error={}", mediaFile, e.getMessage());
            return false;
        }
    }

    private MediaProcessResult applyCustomAudioIfPresent(String audioUrl, Path videoFile, Path tempDir, Long taskId,
                                                         CarSalesVideoDTO request) {
        Double sourceVideoDuration = probeMediaDurationSeconds(videoFile);
        if (!StringUtils.hasText(audioUrl)) {
            return new MediaProcessResult(videoFile, sourceVideoDuration, sourceVideoDuration, null,
                    normalizeSyncStrategy(request));
        }
        Path audioFile = tempDir.resolve("custom-audio-" + taskId + guessMediaExtension(audioUrl, ".mp3"));
        Path outputFile = tempDir.resolve("car-sales-final-" + taskId + "-with-audio.mp4");
        downloadMediaToFile(audioUrl, audioFile, "音频");
        Double audioDuration = probeMediaDurationSeconds(audioFile);
        boolean audioMaster = shouldUseAudioMasterSync(request, audioUrl);
        Path videoForAudio = audioMaster
                ? alignVideoToAudioIfNeeded(videoFile, tempDir, taskId, sourceVideoDuration, audioDuration)
                : videoFile;
        Path audioForMix = audioMaster
                ? audioFile
                : retimeAutoTtsAudioToVisualIfNeeded(audioFile, tempDir, taskId, sourceVideoDuration, audioDuration, request);
        Double finalAudioDuration = audioForMix.equals(audioFile) ? audioDuration : probeMediaDurationSeconds(audioForMix);
        replaceVideoAudio(videoForAudio, audioForMix, outputFile, !audioMaster);
        Double outputDuration = probeMediaDurationSeconds(outputFile);
        log.info("Car sales final audio applied taskId={} syncStrategy={} videoDuration={} audioDuration={} finalAudioDuration={} outputDuration={}",
                taskId, audioMaster ? SYNC_STRATEGY_AUDIO_MASTER : SYNC_STRATEGY_VISUAL_MASTER,
                sourceVideoDuration, audioDuration, finalAudioDuration, outputDuration);
        return new MediaProcessResult(
                outputFile,
                firstPositive(outputDuration, audioMaster ? audioDuration : sourceVideoDuration),
                sourceVideoDuration,
                finalAudioDuration,
                audioMaster ? SYNC_STRATEGY_AUDIO_MASTER : SYNC_STRATEGY_VISUAL_MASTER
        );
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

    private Path alignVideoToAudioIfNeeded(Path videoFile, Path tempDir, Long taskId, Double videoDuration,
                                           Double audioDuration) {
        if (!isPositiveFinite(videoDuration) || !isPositiveFinite(audioDuration)) {
            return videoFile;
        }
        double diffSeconds = Math.abs(videoDuration - audioDuration);
        if (diffSeconds < AUDIO_SYNC_MIN_DIFF_SECONDS) {
            return videoFile;
        }
        Path alignedFile = tempDir.resolve("car-sales-final-" + taskId + "-audio-master-video.mp4");
        alignVideoToAudioDuration(videoFile, alignedFile, videoDuration, audioDuration);
        return alignedFile;
    }

    private void alignVideoToAudioDuration(Path videoFile, Path outputFile, double videoDuration, double audioDuration) {
        double ratio = audioDuration / videoDuration;
        double diffSeconds = audioDuration - videoDuration;
        String filter;
        if (Math.abs(ratio - 1.0) <= AUDIO_SYNC_RETIME_MAX_RATIO_DELTA) {
            filter = "setpts=" + formatFilterNumber(ratio) + "*PTS";
        } else if (diffSeconds > 0) {
            filter = "tpad=stop_mode=clone:stop_duration=" + formatFilterNumber(diffSeconds);
        } else {
            filter = "trim=duration=" + formatFilterNumber(audioDuration) + ",setpts=PTS-STARTPTS";
        }
        Path logFile = outputFile.getParent().resolve("ffmpeg-audio-master-video.log");
        List<String> command = new ArrayList<>(List.of(
                ffmpegPath,
                "-y",
                "-i", videoFile.toString(),
                "-vf", filter,
                "-an",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-movflags", "+faststart",
                outputFile.toString()
        ));
        runMediaCommand(command, logFile, 10, "FFmpeg 音画时长对齐超时", "FFmpeg 音画时长对齐失败");
    }

    private Path retimeAutoTtsAudioToVisualIfNeeded(Path audioFile, Path tempDir, Long taskId,
                                                    Double videoDuration, Double audioDuration,
                                                    CarSalesVideoDTO request) {
        if (!shouldRetimeAutoTtsAudio(request)
                || !isPositiveFinite(videoDuration)
                || !isPositiveFinite(audioDuration)) {
            return audioFile;
        }
        double diffSeconds = Math.abs(audioDuration - videoDuration);
        if (diffSeconds < AUDIO_SYNC_MIN_DIFF_SECONDS) {
            return audioFile;
        }
        double speedFactor = Math.max(0.5, Math.min(2.0, audioDuration / videoDuration));
        if (Math.abs(speedFactor - 1.0) < 0.01) {
            return audioFile;
        }
        Path retimedFile = tempDir.resolve("car-sales-final-" + taskId + "-tts-visual-master-audio.m4a");
        retimeAudioTempo(audioFile, retimedFile, speedFactor);
        log.info("Auto TTS audio retimed to visual duration taskId={} videoDuration={} audioDuration={} speedFactor={}",
                taskId, videoDuration, audioDuration, speedFactor);
        return retimedFile;
    }

    private boolean shouldRetimeAutoTtsAudio(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        return "auto_tts".equalsIgnoreCase(trimToDefault(request.getVoicePolicy(), ""))
                || request.getGeneratedVoiceAssetId() != null
                || StringUtils.hasText(request.getGeneratedVoiceUrl());
    }

    private void retimeAudioTempo(Path audioFile, Path outputFile, double speedFactor) {
        String filter = buildAtempoFilter(speedFactor);
        Path logFile = outputFile.getParent().resolve("ffmpeg-auto-tts-tempo.log");
        List<String> command = new ArrayList<>(List.of(
                ffmpegPath,
                "-y",
                "-i", audioFile.toString(),
                "-vn",
                "-filter:a", filter,
                "-c:a", "aac",
                "-b:a", "192k",
                outputFile.toString()
        ));
        runMediaCommand(command, logFile, 10, "FFmpeg 自动口播语速校准超时", "FFmpeg 自动口播语速校准失败");
    }

    private String buildAtempoFilter(double speedFactor) {
        double factor = Math.max(0.5, Math.min(2.0, speedFactor));
        return "atempo=" + formatFilterNumber(factor);
    }

    private void replaceVideoAudio(Path videoFile, Path audioFile, Path outputFile, boolean visualMaster) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-audio.log");
        List<String> command = new ArrayList<>(List.of(
                ffmpegPath,
                "-y",
                "-i", videoFile.toString(),
                "-i", audioFile.toString(),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k"
        ));
        if (visualMaster) {
            command.add("-af");
            command.add("apad");
        }
        command.add("-shortest");
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputFile.toString());
        runMediaCommand(command, logFile, 10, "FFmpeg 音频合成超时", "FFmpeg 音频合成失败");
    }

    private void runMediaCommand(List<String> command, Path logFile, long timeoutMinutes,
                                 String timeoutMessage, String failureMessage) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, timeoutMessage);
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, failureMessage + "：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50100, failureMessage + "：任务已中断");
        } catch (Exception e) {
            throw new BusinessException(50100, failureMessage + "：" + e.getMessage());
        }
    }

    private Double probeMediaDurationSeconds(Path mediaFile) {
        if (mediaFile == null || !Files.exists(mediaFile)) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaFile.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("FFprobe duration timeout file={}", mediaFile);
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || !StringUtils.hasText(output) || "N/A".equalsIgnoreCase(output)) {
                log.warn("FFprobe duration failed file={} output={}", mediaFile, trimPrompt(output, 200));
                return null;
            }
            double duration = Double.parseDouble(output);
            return isPositiveFinite(duration) ? duration : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FFprobe duration interrupted file={}", mediaFile);
            return null;
        } catch (Exception e) {
            log.warn("FFprobe duration failed file={} error={}", mediaFile, e.getMessage());
            return null;
        }
    }

    private String formatFilterNumber(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private boolean isPositiveFinite(Double value) {
        return value != null && isPositiveFinite(value.doubleValue());
    }

    private boolean isPositiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private Double firstPositive(Double preferred, Double fallback) {
        if (isPositiveFinite(preferred)) {
            return preferred;
        }
        return isPositiveFinite(fallback) ? fallback : null;
    }

    private BigDecimal mediaDurationOrFallback(Double durationSeconds, BigDecimal fallback) {
        if (isPositiveFinite(durationSeconds)) {
            return BigDecimal.valueOf(durationSeconds);
        }
        return fallback == null ? BigDecimal.ZERO : fallback;
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

    private Path burnSubtitlesIfNeeded(CarSalesVideoDTO request, Path videoFile, Path subtitleAudioSourceFile,
                                       Path tempDir, Long taskId, BigDecimal totalDuration,
                                       List<CarSalesVideoDTO.Scene> scenes) {
        boolean audioRecognitionTried = false;
        if (shouldUseAudioRecognitionSubtitleTiming(request, scenes)) {
            audioRecognitionTried = true;
            try {
                return burnUploadSubtitleWithVolcengine(request, videoFile, subtitleAudioSourceFile, tempDir, taskId);
            } catch (BusinessException e) {
                if (isForcedAudioRecognitionSubtitleTiming(request)) {
                    throw e;
                }
                log.warn("Car sales audio-recognition subtitle failed, skip burned subtitle taskId={} error={}",
                        taskId, e.getMessage());
                return videoFile;
            }
        }
        String subtitleText = resolveBurnedSubtitleText(request, scenes);
        if (!StringUtils.hasText(subtitleText)) {
            if (!audioRecognitionTried && shouldFallbackToAudioRecognitionSubtitle(request)) {
                return burnUploadSubtitleWithVolcengine(request, videoFile, subtitleAudioSourceFile, tempDir, taskId);
            }
            return videoFile;
        }
        double durationSeconds = resolveSubtitleDurationSeconds(totalDuration, request, scenes);
        Path assFile = tempDir.resolve("car-sales-subtitle-" + taskId + ".ass");
        Path outputFile = tempDir.resolve("car-sales-final-" + taskId + "-with-subtitle.mp4");
        if (shouldBurnSubtitleByScenes(request, scenes)) {
            writeAssSubtitleByScenes(assFile, scenes, durationSeconds, request);
        } else {
            writeAssSubtitle(assFile, subtitleText, durationSeconds, request);
        }
        burnAssSubtitle(videoFile, assFile, outputFile);
        return outputFile;
    }

    private String resolveBurnedSubtitleText(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (request == null) {
            return null;
        }
        String subtitle = normalizeSubtitle(request.getSubtitle());
        if (isSubtitleDisabled(request)) {
            return null;
        }
        if (hasExplicitSubtitleText(subtitle)) {
            ensureNoGarbledSpeechText(subtitle, 40000, "字幕文案");
            return subtitle;
        }
        if (isAutoSubtitleRequested(request)) {
            return resolveAutoSubtitleText(request, scenes);
        }
        return null;
    }

    private String resolveAutoSubtitleText(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (!SUBTITLE_TIMING_SCRIPT_TIMELINE.equals(normalizeSubtitleTimingMode(request))) {
            return null;
        }
        String text = firstNonBlank(request == null ? null : request.getFinalVoiceText(), collectSceneVoiceText(scenes));
        if (!shouldUseTextSubtitleForNativeNarration(request, text)) {
            return null;
        }
        ensureNoGarbledSpeechText(text, 40000, "自动字幕文案");
        return text;
    }

    private String normalizeSubtitleTimingMode(CarSalesVideoDTO request) {
        String mode = trimToDefault(request == null ? null : request.getSubtitleTimingMode(), SUBTITLE_TIMING_AUTO)
                .toLowerCase(Locale.ROOT);
        return switch (mode) {
            case SUBTITLE_TIMING_AUDIO_RECOGNITION, SUBTITLE_TIMING_SCRIPT_TIMELINE -> mode;
            default -> SUBTITLE_TIMING_AUTO;
        };
    }

    private boolean shouldUseAudioRecognitionSubtitleTiming(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (request == null) {
            return false;
        }
        String mode = normalizeSubtitleTimingMode(request);
        if (SUBTITLE_TIMING_SCRIPT_TIMELINE.equals(mode)) {
            return false;
        }
        if (SUBTITLE_TIMING_AUDIO_RECOGNITION.equals(mode)) {
            return isAutoSubtitleRequested(request) && hasFinalNarrationAudio(request);
        }
        if (isAutoSubtitleRequested(request) && hasFinalNarrationAudio(request)) {
            return true;
        }
        if (hasScriptTimelineSubtitleSource(request, scenes)) {
            return false;
        }
        return isAutoSubtitleRequested(request) && hasFinalNarrationAudio(request);
    }

    private boolean hasScriptTimelineSubtitleSource(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (request == null) {
            return false;
        }
        String subtitle = normalizeSubtitle(request.getSubtitle());
        if (hasExplicitSubtitleText(subtitle)) {
            return true;
        }
        String text = firstNonBlank(request.getFinalVoiceText(), collectSceneVoiceText(scenes));
        return shouldUseTextSubtitleForNativeNarration(request, text);
    }

    private boolean isForcedAudioRecognitionSubtitleTiming(CarSalesVideoDTO request) {
        return SUBTITLE_TIMING_AUDIO_RECOGNITION.equals(normalizeSubtitleTimingMode(request));
    }

    private boolean shouldFallbackToAudioRecognitionSubtitle(CarSalesVideoDTO request) {
        return !SUBTITLE_TIMING_SCRIPT_TIMELINE.equals(normalizeSubtitleTimingMode(request))
                && isAutoSubtitleRequested(request)
                && hasFinalNarrationAudio(request);
    }

    private boolean hasFinalNarrationAudio(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        return shouldUseFinalAudio(request)
                || shouldGenerateNativeAudio(request)
                || "auto_tts".equalsIgnoreCase(trimToDefault(request.getVoicePolicy(), ""))
                || request.getGeneratedVoiceAssetId() != null
                || StringUtils.hasText(request.getGeneratedVoiceUrl());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean shouldBurnSubtitleByScenes(CarSalesVideoDTO request, List<CarSalesVideoDTO.Scene> scenes) {
        if (hasFinalNarrationAudio(request)) {
            return false;
        }
        String sceneVoiceText = collectSceneVoiceText(scenes);
        if (!hasSceneVoiceText(scenes) || !shouldUseTextSubtitleForNativeNarration(request, sceneVoiceText)) {
            return false;
        }
        String subtitle = normalizeSubtitle(request == null ? null : request.getSubtitle());
        return isAutoSubtitle(subtitle)
                || isPostAutoSubtitleMode(request)
                || sameNormalizedSubtitle(subtitle, sceneVoiceText)
                || sameNormalizedSubtitle(request == null ? null : request.getFinalVoiceText(), sceneVoiceText);
    }

    private boolean shouldUseTextSubtitleForNativeNarration(CarSalesVideoDTO request, String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (isEnglishNarration(request)) {
            return looksLikeEnglishText(text);
        }
        return true;
    }

    private boolean looksLikeEnglishText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        int latin = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.HAN) {
                cjk++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                latin++;
            }
        }
        return latin >= 12 && latin >= cjk * 2;
    }

    private boolean sameNormalizedSubtitle(String left, String right) {
        String cleanLeft = cleanSpeechText(left);
        String cleanRight = cleanSpeechText(right);
        if (!StringUtils.hasText(cleanLeft) || !StringUtils.hasText(cleanRight)) {
            return false;
        }
        return cleanLeft.replaceAll("\\s+", "").equals(cleanRight.replaceAll("\\s+", ""));
    }

    private Path burnUploadSubtitleWithVolcengine(CarSalesVideoDTO request, Path videoFile, Path tempDir, Long taskId) {
        return burnUploadSubtitleWithVolcengine(request, videoFile, videoFile, tempDir, taskId);
    }

    private Path burnUploadSubtitleWithVolcengine(CarSalesVideoDTO request, Path videoFile, Path subtitleAudioSourceFile,
                                                 Path tempDir, Long taskId) {
        Path audioFile = tempDir.resolve("car-sales-subtitle-audio-" + taskId + ".wav");
        Path srtFile = tempDir.resolve("car-sales-subtitle-" + taskId + ".srt");
        Path outputFile = tempDir.resolve("car-sales-final-" + taskId + "-with-volc-subtitle.mp4");
        Path audioSourceFile = subtitleAudioSourceFile == null ? videoFile : subtitleAudioSourceFile;
        extractAudioForSubtitle(audioSourceFile, audioFile);
        UploadResult audio = uploadSubtitleAudio(audioFile, taskId);
        String language = subtitleRecognitionLanguage(request);
        VolcengineSubtitleClient.SubtitleResult subtitle = volcengineSubtitleClient.createSrtFromAudioUrl(
                audio.url(), language);
        try {
            String alignedSrt = alignCanonicalTextToSrtTiming(subtitle.srt(),
                    trustedScriptSubtitleTextForAudioTiming(request));
            Files.writeString(srtFile, wrapSrtSubtitleLines(alignedSrt, request), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(50100, "写入火山字幕 SRT 失败：" + e.getMessage());
        }
        burnSrtSubtitle(videoFile, srtFile, outputFile, request);
        log.info("Car sales auto subtitle burned by Volcengine taskId={} subtitleJobId={} language={}",
                taskId, subtitle.jobId(), language);
        return outputFile;
    }

    private String trustedScriptSubtitleTextForAudioTiming(CarSalesVideoDTO request) {
        if (!hasGeneratedVoiceAudio(request)) {
            return null;
        }
        String text = cleanSpeechText(request == null ? null : request.getFinalVoiceText());
        if (!StringUtils.hasText(text) || !shouldUseTextSubtitleForNativeNarration(request, text)) {
            return null;
        }
        return text;
    }

    private boolean hasGeneratedVoiceAudio(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        return isAutoTtsVoicePolicy(request)
                || request.getGeneratedVoiceAssetId() != null
                || StringUtils.hasText(request.getGeneratedVoiceUrl());
    }

    private String alignCanonicalTextToSrtTiming(String recognizedSrt, String canonicalText) {
        if (!StringUtils.hasText(recognizedSrt)) {
            return recognizedSrt;
        }
        List<SrtCue> cues = parseSrtCues(recognizedSrt);
        if (cues.isEmpty()) {
            return recognizedSrt;
        }
        List<SrtCue> sentenceCues = shouldMergeRecognizedSubtitleCues(cues)
                ? mergeSrtCuesBySentence(cues)
                : cues;
        if (!StringUtils.hasText(canonicalText)) {
            String sentenceSrt = formatSrtCues(sentenceCues);
            return StringUtils.hasText(sentenceSrt) ? sentenceSrt : recognizedSrt;
        }
        List<String> chunks = splitCanonicalSubtitleByCueWeights(canonicalText, sentenceCues);
        if (chunks.isEmpty()) {
            return recognizedSrt;
        }
        StringBuilder srt = new StringBuilder();
        for (int i = 0; i < sentenceCues.size() && i < chunks.size(); i++) {
            String text = cleanSpeechText(chunks.get(i));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            SrtCue cue = sentenceCues.get(i);
            srt.append(i + 1).append('\n')
                    .append(cue.start()).append(" --> ").append(cue.end()).append('\n')
                    .append(text)
                    .append("\n\n");
        }
        return StringUtils.hasText(srt.toString()) ? srt.toString() : recognizedSrt;
    }

    private String wrapSrtSubtitleLines(String srt, CarSalesVideoDTO request) {
        if (!StringUtils.hasText(srt)) {
            return srt;
        }
        List<SrtCue> cues = parseSrtCues(srt);
        if (cues.isEmpty()) {
            return srt;
        }
        int maxWeight = subtitleSafeLineWeight(request);
        StringBuilder wrapped = new StringBuilder();
        int index = 1;
        for (SrtCue cue : cues) {
            String text = cleanSpeechText(cue == null ? null : cue.text());
            if (cue == null || !StringUtils.hasText(text) || isSubtitlePunctuationOnly(text)) {
                continue;
            }
            wrapped.append(index++).append('\n')
                    .append(cue.start()).append(" --> ").append(cue.end()).append('\n')
                    .append(String.join("\n", splitLongSubtitleUnit(text, maxWeight)))
                    .append("\n\n");
        }
        return StringUtils.hasText(wrapped.toString()) ? wrapped.toString() : srt;
    }

    private List<SrtCue> mergeSrtCuesBySentence(List<SrtCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        List<SrtCue> merged = new ArrayList<>();
        String start = null;
        String end = null;
        StringBuilder text = new StringBuilder();
        for (SrtCue cue : cues) {
            if (cue == null || !StringUtils.hasText(cue.text())) {
                continue;
            }
            if (!StringUtils.hasText(start)) {
                start = cue.start();
            }
            end = cue.end();
            String clean = cleanSpeechText(cue.text());
            if (!StringUtils.hasText(clean)) {
                continue;
            }
            if (text.isEmpty()) {
                text.append(clean);
            } else {
                String joined = joinSubtitleText(text.toString(), clean);
                text.setLength(0);
                text.append(joined);
            }
            if (shouldCloseMergedSubtitleCue(text.toString())) {
                merged.add(new SrtCue(start, end, text.toString().trim()));
                start = null;
                end = null;
                text.setLength(0);
            }
        }
        if (!text.isEmpty() && StringUtils.hasText(start) && StringUtils.hasText(end)) {
            merged.add(new SrtCue(start, end, text.toString().trim()));
        }
        return merged.isEmpty() ? cues : merged;
    }

    private boolean shouldMergeRecognizedSubtitleCues(List<SrtCue> cues) {
        if (cues == null || cues.size() < 3) {
            return false;
        }
        int total = 0;
        int tiny = 0;
        int noSentenceBreak = 0;
        int totalWeight = 0;
        for (SrtCue cue : cues) {
            String text = cleanSpeechText(cue == null ? null : cue.text());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            int weight = subtitleWeight(text);
            total++;
            totalWeight += weight;
            if (weight <= 8) {
                tiny++;
            }
            if (!endsWithSubtitleSentenceBreak(text)) {
                noSentenceBreak++;
            }
        }
        if (total < 3) {
            return false;
        }
        double averageWeight = totalWeight / (double) total;
        return tiny >= Math.max(2, (int) Math.ceil(total * 0.6D))
                || (noSentenceBreak == total && averageWeight <= 10D);
    }

    private boolean shouldCloseMergedSubtitleCue(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (endsWithSubtitleSentenceBreak(text)) {
            return true;
        }
        return subtitleDisplayWeight(text) >= 56;
    }

    private String formatSrtCues(List<SrtCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return "";
        }
        StringBuilder srt = new StringBuilder();
        int index = 1;
        for (SrtCue cue : cues) {
            String text = cleanSpeechText(cue == null ? null : cue.text());
            if (cue == null || !StringUtils.hasText(text)) {
                continue;
            }
            srt.append(index++).append('\n')
                    .append(cue.start()).append(" --> ").append(cue.end()).append('\n')
                    .append(text)
                    .append("\n\n");
        }
        return srt.toString();
    }

    private List<SrtCue> parseSrtCues(String srt) {
        if (!StringUtils.hasText(srt)) {
            return List.of();
        }
        List<SrtCue> cues = new ArrayList<>();
        String normalized = srt.replace("\r\n", "\n").replace('\r', '\n');
        for (String block : normalized.split("\\n\\s*\\n")) {
            String[] lines = block.split("\\n");
            String timing = null;
            List<String> textLines = new ArrayList<>();
            for (String line : lines) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.contains("-->")) {
                    timing = trimmed;
                    continue;
                }
                if (timing != null) {
                    textLines.add(trimmed);
                }
            }
            if (!StringUtils.hasText(timing)) {
                continue;
            }
            String[] parts = timing.split("\\s*-->\\s*");
            if (parts.length != 2) {
                continue;
            }
            String text = cleanSpeechText(String.join(" ", textLines));
            if (StringUtils.hasText(text)) {
                cues.add(new SrtCue(parts[0].trim(), parts[1].trim(), text));
            }
        }
        return cues;
    }

    private List<String> splitCanonicalSubtitleByCueWeights(String canonicalText, List<SrtCue> cues) {
        String clean = cleanSpeechText(canonicalText);
        if (!StringUtils.hasText(clean) || cues == null || cues.isEmpty()) {
            return List.of();
        }
        if (cues.size() == 1) {
            return List.of(clean);
        }
        int totalWeight = cues.stream()
                .mapToInt(cue -> Math.max(1, subtitleWeight(cue.text())))
                .sum();
        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        int consumedWeight = 0;
        for (int i = 0; i < cues.size() - 1; i++) {
            consumedWeight += Math.max(1, subtitleWeight(cues.get(i).text()));
            int preferredEnd = (int) Math.round(clean.length() * consumedWeight / (double) Math.max(1, totalWeight));
            int end = Math.max(cursor + 1, smartVoiceSplitBoundary(clean, cursor,
                    Math.max(cursor + 1, preferredEnd)));
            end = Math.min(end, clean.length());
            String chunk = clean.substring(cursor, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            cursor = skipVoiceWhitespace(clean, end);
            if (cursor >= clean.length()) {
                break;
            }
        }
        String tail = clean.substring(Math.min(cursor, clean.length())).trim();
        if (StringUtils.hasText(tail)) {
            chunks.add(tail);
        }
        return fitSubtitleChunksToCount(chunks, cues.size());
    }

    private List<String> fitSubtitleChunksToCount(List<String> chunks, int total) {
        int count = Math.max(1, total);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> clean = chunks.stream()
                .map(this::cleanSpeechText)
                .filter(StringUtils::hasText)
                .toList();
        if (clean.size() == count) {
            return new ArrayList<>(clean);
        }
        if (clean.size() < count) {
            List<String> result = new ArrayList<>(clean);
            while (result.size() < count) {
                result.add("");
            }
            return result;
        }
        List<String> fitted = new ArrayList<>(clean.subList(0, count - 1));
        StringBuilder tail = new StringBuilder();
        for (String chunk : clean.subList(count - 1, clean.size())) {
            if (tail.isEmpty()) {
                tail.append(chunk);
            } else {
                String merged = joinSubtitleText(tail.toString(), chunk);
                tail.setLength(0);
                tail.append(merged);
            }
        }
        if (!tail.isEmpty()) {
            fitted.add(tail.toString());
        }
        return fitted;
    }

    private void extractAudioForSubtitle(Path videoFile, Path audioFile) {
        Path logFile = audioFile.getParent().resolve("ffmpeg-subtitle-audio.log");
        try {
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-vn",
                    "-ac", "1",
                    "-ar", "16000",
                    "-acodec", "pcm_s16le",
                    audioFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg 字幕音频提取超时");
            }
            if (process.exitValue() != 0 || !Files.exists(audioFile) || Files.size(audioFile) <= 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg 字幕音频提取失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg 字幕音频提取失败：" + e.getMessage());
        }
    }

    private UploadResult uploadSubtitleAudio(Path audioFile, Long taskId) {
        try (InputStream in = Files.newInputStream(audioFile)) {
            return storageService.upload(
                    in,
                    Files.size(audioFile),
                    "car-sales-subtitle-audio-" + taskId + ".wav",
                    "audio/wav",
                    "video"
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50000, "上传字幕识别音频失败：" + e.getMessage());
        }
    }

    private void burnSrtSubtitle(Path videoFile, Path srtFile, Path outputFile, CarSalesVideoDTO request) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-srt-subtitle.log");
        try {
            SubtitleLayout layout = subtitleLayout(request);
            SubtitleFont subtitleFont = resolveSubtitleFont();
            String fontName = subtitleFontNameForStyle(request, subtitleFont);
            String filter = "subtitles=filename='" + escapeSubtitleFilterPath(srtFile)
                    + "'"
                    + subtitleFontsDirFilter(subtitleFont)
                    + ":charenc=UTF-8:force_style='FontName=" + fontName + ",FontSize=" + layout.srtFontSize()
                    + ",PrimaryColour=" + layout.primaryColour() + ",OutlineColour=" + layout.outlineColour()
                    + ",BorderStyle=1,Outline=" + layout.srtOutline() + ",Shadow=1,Alignment=" + layout.alignment()
                    + ",MarginL=" + layout.assMarginH() + ",MarginR=" + layout.assMarginH()
                    + ",MarginV=" + layout.srtMarginV() + "'";
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-vf", filter,
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "20",
                    "-c:a", "copy",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg SRT 字幕烧录超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg SRT 字幕烧录失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg SRT 字幕烧录失败：" + e.getMessage());
        }
    }

    private SubtitleLayout subtitleLayout(CarSalesVideoDTO request) {
        String ratio = request == null ? "" : trimToDefault(request.getAspectRatio(), "");
        boolean wide = isWideAspectRatio(ratio);
        int assFontSize = normalizeSubtitleFontSize(
                request == null || request.getSubtitleOverlay() == null ? null : request.getSubtitleOverlay().getFontSize(),
                DEFAULT_SUBTITLE_FONT_SIZE,
                wide);
        int srtFontSize = normalizeSrtSubtitleFontSize(assFontSize, wide);
        String position = subtitlePosition(request);
        int alignment = subtitleAlignment(position);
        int assMarginV = subtitleMarginV(position, wide, true);
        int srtMarginV = subtitleMarginV(position, wide, false);
        int assOutline = Math.max(2, Math.min(8, Math.round(assFontSize / 14.0f)));
        int srtOutline = Math.max(1, Math.min(2, Math.round(srtFontSize / 10.0f)));
        String primaryColour = normalizeAssColor(
                request == null || request.getSubtitleOverlay() == null ? null : request.getSubtitleOverlay().getTextColor(),
                "&H00FFFFFF");
        String outlineColour = normalizeAssColor(
                request == null || request.getSubtitleOverlay() == null ? null : request.getSubtitleOverlay().getOutlineColor(),
                "&H00111111");
        return new SubtitleLayout(
                wide ? 1920 : 1080,
                wide ? 1080 : 1920,
                assFontSize,
                wide ? 96 : 104,
                assMarginV,
                srtFontSize,
                srtMarginV,
                alignment,
                primaryColour,
                outlineColour,
                assOutline,
                srtOutline);
    }

    private int subtitleMarginV(String position, boolean wide, boolean assSubtitle) {
        if ("middle".equals(position)) {
            return 0;
        }
        if ("bottom".equals(position)) {
            return assSubtitle
                    ? (wide ? 44 : 64)
                    : (wide ? 52 : 110);
        }
        return wide ? 82 : (assSubtitle ? 170 : 220);
    }

    private int normalizeSubtitleFontSize(Integer value, int fallback, boolean wide) {
        int size = value == null || value <= 0 ? fallback : value;
        int min = wide ? 18 : 16;
        int max = wide ? 34 : 24;
        return Math.max(min, Math.min(max, size));
    }

    private int subtitleSafeLineWeight(CarSalesVideoDTO request) {
        return isWideAspectRatio(request == null ? null : request.getAspectRatio()) ? 36 : 14;
    }

    private boolean isSubtitlePunctuationOnly(String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            int type = Character.getType(text.charAt(i));
            if (type != Character.CONNECTOR_PUNCTUATION
                    && type != Character.DASH_PUNCTUATION
                    && type != Character.START_PUNCTUATION
                    && type != Character.END_PUNCTUATION
                    && type != Character.INITIAL_QUOTE_PUNCTUATION
                    && type != Character.FINAL_QUOTE_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION
                    && !Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int normalizeSrtSubtitleFontSize(int assFontSize, boolean wide) {
        int size = Math.round(assFontSize * (wide ? 0.62f : 0.50f));
        return wide
                ? Math.max(14, Math.min(20, size))
                : Math.max(9, Math.min(12, size));
    }

    private String subtitlePosition(CarSalesVideoDTO request) {
        CarSalesVideoDTO.TextOverlay overlay = request == null ? null : request.getSubtitleOverlay();
        return normalizeOverlayPosition(overlay == null ? null : overlay.getPosition(), "bottom");
    }

    private int subtitleAlignment(String position) {
        return switch (normalizeOverlayPosition(position, "bottom")) {
            case "top" -> 8;
            case "middle" -> 5;
            default -> 2;
        };
    }

    private String normalizeOverlayPosition(String value, String fallback) {
        String position = trimToDefault(value, fallback).toLowerCase(Locale.ROOT);
        return switch (position) {
            case "top" -> "top";
            case "middle", "center", "centre" -> "middle";
            case "bottom" -> "bottom";
            default -> fallback;
        };
    }

    private String subtitleFontNameForStyle(CarSalesVideoDTO request, SubtitleFont subtitleFont) {
        String requested = trimToNull(request == null || request.getSubtitleOverlay() == null
                ? null : request.getSubtitleOverlay().getFontFamily());
        String fontName = StringUtils.hasText(requested)
                ? requested
                : DEFAULT_SUBTITLE_FONT_FAMILY;
        return sanitizeAssStyleValue(fontName);
    }

    private String sanitizeAssStyleValue(String value) {
        return trimToDefault(value, DEFAULT_SUBTITLE_FONT_FAMILY)
                .replace(",", " ")
                .replace("'", "")
                .trim();
    }

    private String normalizeAssColor(String value, String fallback) {
        String clean = trimToNull(value);
        if (!StringUtils.hasText(clean)) {
            return fallback;
        }
        String hex = clean.startsWith("#") ? clean.substring(1) : clean;
        if (hex.matches("[0-9a-fA-F]{3}")) {
            hex = "" + hex.charAt(0) + hex.charAt(0)
                    + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return fallback;
        }
        String upper = hex.toUpperCase(Locale.ROOT);
        return "&H00" + upper.substring(4, 6) + upper.substring(2, 4) + upper.substring(0, 2);
    }

    private String normalizeSubtitleLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "zh-CN";
        }
        return switch (language.trim()) {
            case "en-US", "zh-CN" -> language.trim();
            default -> "zh-CN";
        };
    }

    private String subtitleRecognitionLanguage(CarSalesVideoDTO request) {
        if (isEnglishNarration(request)) {
            return "en-US";
        }
        return normalizeSubtitleLanguage(request == null ? null : request.getSubtitleLanguage());
    }

    private String collectSceneVoiceText(List<CarSalesVideoDTO.Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (CarSalesVideoDTO.Scene scene : scenes) {
            if (scene != null && StringUtils.hasText(scene.getVoiceText())) {
                String clean = cleanSpeechText(scene.getVoiceText());
                if (StringUtils.hasText(clean)) {
                    lines.add(clean);
                }
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private double resolveSubtitleDurationSeconds(BigDecimal totalDuration, CarSalesVideoDTO request,
                                                  List<CarSalesVideoDTO.Scene> scenes) {
        if (totalDuration != null && totalDuration.signum() > 0) {
            return Math.max(1.0, totalDuration.doubleValue());
        }
        int sceneCount = scenes == null || scenes.isEmpty()
                ? normalizeSegmentCount(request == null ? null : request.getSegmentCount())
                : scenes.size();
        int segmentDuration = normalizeSegmentDuration(request == null ? null : request.getSegmentDuration(),
                request == null ? null : request.getModel());
        return Math.max(1.0, (double) sceneCount * segmentDuration);
    }

    private boolean hasSceneVoiceText(List<CarSalesVideoDTO.Scene> scenes) {
        return scenes != null && scenes.stream()
                .anyMatch(scene -> scene != null && StringUtils.hasText(scene.getVoiceText()));
    }

    private void writeAssSubtitleByScenes(Path assFile, List<CarSalesVideoDTO.Scene> scenes,
                                          double durationSeconds, CarSalesVideoDTO request) {
        try {
            List<CarSalesVideoDTO.Scene> usableScenes = scenes == null ? List.of() : scenes.stream()
                    .filter(scene -> scene != null && StringUtils.hasText(scene.getVoiceText()))
                    .toList();
            if (usableScenes.isEmpty()) {
                return;
            }
            StringBuilder ass = new StringBuilder();
            appendAssHeader(ass, request);
            double totalSceneDuration = usableScenes.stream()
                    .mapToDouble(scene -> normalizeSegmentDuration(scene.getDuration(),
                            request == null ? null : request.getModel()))
                    .sum();
            if (totalSceneDuration <= 0) {
                totalSceneDuration = durationSeconds;
            }
            double cursor = 0.0;
            for (int sceneIndex = 0; sceneIndex < usableScenes.size(); sceneIndex++) {
                CarSalesVideoDTO.Scene scene = usableScenes.get(sceneIndex);
                double sceneDuration = sceneIndex == usableScenes.size() - 1
                        ? durationSeconds - cursor
                        : durationSeconds * normalizeSegmentDuration(scene.getDuration(),
                        request == null ? null : request.getModel()) / Math.max(1.0, totalSceneDuration);
                double sceneEnd = sceneIndex == usableScenes.size() - 1
                        ? durationSeconds
                        : Math.min(durationSeconds, cursor + Math.max(1.0, sceneDuration));
                appendAssDialogues(ass, splitSubtitleChunks(scene.getVoiceText()), cursor, sceneEnd);
                cursor = sceneEnd;
            }
            Files.writeString(assFile, ass.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(50100, "生成字幕文件失败：" + e.getMessage());
        }
    }

    private void appendAssHeader(StringBuilder ass, CarSalesVideoDTO request) {
        SubtitleLayout layout = subtitleLayout(request);
        SubtitleFont subtitleFont = resolveSubtitleFont();
        String fontName = subtitleFontNameForStyle(request, subtitleFont);
        ass.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: ").append(layout.playResX()).append('\n')
                .append("PlayResY: ").append(layout.playResY()).append('\n')
                .append("WrapStyle: 2\n")
                .append("ScaledBorderAndShadow: yes\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
                .append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, ")
                .append("Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,").append(fontName).append(',').append(layout.assFontSize()).append(',')
                .append(layout.primaryColour()).append(',').append(layout.primaryColour()).append(',')
                .append(layout.outlineColour()).append(",&H99000000,")
                .append("1,0,0,0,100,100,0,0,1,").append(layout.assOutline()).append(",1,")
                .append(layout.alignment()).append(',')
                .append(layout.assMarginH()).append(',').append(layout.assMarginH()).append(',').append(layout.assMarginV()).append(",1\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
    }

    private void appendAssDialogues(StringBuilder ass, List<String> chunks, double startSeconds, double endSeconds) {
        if (chunks == null || chunks.isEmpty() || endSeconds <= startSeconds) {
            return;
        }
        int totalWeight = chunks.stream().mapToInt(this::subtitleWeight).sum();
        double cursor = startSeconds;
        double durationSeconds = endSeconds - startSeconds;
        for (int i = 0; i < chunks.size(); i++) {
            double segment = i == chunks.size() - 1
                    ? endSeconds - cursor
                    : durationSeconds * subtitleWeight(chunks.get(i)) / Math.max(1, totalWeight);
            segment = Math.max(1.2, segment);
            double end = i == chunks.size() - 1 ? endSeconds : Math.min(endSeconds, cursor + segment);
            if (end <= cursor) {
                break;
            }
            ass.append("Dialogue: 0,")
                    .append(formatAssTime(cursor))
                    .append(",")
                    .append(formatAssTime(end))
                    .append(",Default,,0,0,0,,")
                    .append(escapeAssText(chunks.get(i)))
                    .append('\n');
            cursor = end;
        }
    }

    private void writeAssSubtitle(Path assFile, String subtitleText, double durationSeconds, CarSalesVideoDTO request) {
        try {
            List<String> chunks = splitSubtitleChunks(subtitleText);
            if (chunks.isEmpty()) {
                return;
            }
            StringBuilder ass = new StringBuilder();
            appendAssHeader(ass, request);

            int totalWeight = chunks.stream().mapToInt(this::subtitleWeight).sum();
            double cursor = 0.0;
            for (int i = 0; i < chunks.size(); i++) {
                double segment = i == chunks.size() - 1
                        ? durationSeconds - cursor
                        : durationSeconds * subtitleWeight(chunks.get(i)) / Math.max(1, totalWeight);
                segment = Math.max(1.2, segment);
                double end = i == chunks.size() - 1 ? durationSeconds : Math.min(durationSeconds, cursor + segment);
                if (end <= cursor) {
                    break;
                }
                ass.append("Dialogue: 0,")
                        .append(formatAssTime(cursor))
                        .append(",")
                        .append(formatAssTime(end))
                        .append(",Default,,0,0,0,,")
                        .append(escapeAssText(chunks.get(i)))
                        .append('\n');
                cursor = end;
            }
            Files.writeString(assFile, ass.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(50100, "生成字幕文件失败：" + e.getMessage());
        }
    }

    private List<String> splitSubtitleChunks(String text) {
        String normalized = cleanSpeechText(text);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (String paragraph : normalized.split("\\n+")) {
            String trimmed = paragraph.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String unit : splitSubtitleUnits(trimmed)) {
                for (String piece : splitLongSubtitleUnit(unit, 36)) {
                    String candidate = current.isEmpty()
                            ? piece
                            : joinSubtitleText(current.toString(), piece);
                    if (!current.isEmpty() && subtitleDisplayWeight(candidate) > 36) {
                        chunks.add(current.toString().trim());
                        current.setLength(0);
                        current.append(piece);
                    } else {
                        current.setLength(0);
                        current.append(candidate);
                    }
                }
                if (!current.isEmpty() && endsWithSubtitleBreak(current.toString())) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
            }
            if (!current.isEmpty()) {
                chunks.add(current.toString().trim());
            }
        }
        return chunks;
    }

    private List<String> splitSubtitleUnits(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            current.appendCodePoint(codePoint);
            int boundary = i + charCount;
            if (isSubtitleBreakChar(codePoint) && isSafeSpeechBoundary(text, boundary)) {
                units.add(current.toString().trim());
                current.setLength(0);
            }
            i = boundary;
        }
        if (!current.isEmpty()) {
            units.add(current.toString().trim());
        }
        return units.stream().filter(StringUtils::hasText).toList();
    }

    private List<String> splitLongSubtitleUnit(String text, int maxWeight) {
        if (!StringUtils.hasText(text) || subtitleDisplayWeight(text) <= maxWeight) {
            return StringUtils.hasText(text) ? List.of(text.trim()) : List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : subtitleTokens(text)) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String candidate = current.isEmpty()
                    ? token
                    : joinSubtitleText(current.toString(), token);
            if (!current.isEmpty() && subtitleDisplayWeight(candidate) > maxWeight) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                current.append(token);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> subtitleTokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                flushToken(tokens, ascii);
            } else if (isAsciiWordCodePoint(codePoint)) {
                ascii.appendCodePoint(codePoint);
            } else {
                flushToken(tokens, ascii);
                tokens.add(new String(Character.toChars(codePoint)));
            }
            i += charCount;
        }
        flushToken(tokens, ascii);
        return tokens;
    }

    private void flushToken(List<String> tokens, StringBuilder token) {
        if (!token.isEmpty()) {
            tokens.add(token.toString());
            token.setLength(0);
        }
    }

    private String joinSubtitleText(String left, String right) {
        if (!StringUtils.hasText(left)) {
            return right;
        }
        if (!StringUtils.hasText(right)) {
            return left;
        }
        char last = left.charAt(left.length() - 1);
        char beforeLast = left.length() >= 2 ? left.charAt(left.length() - 2) : '\0';
        char first = right.charAt(0);
        return shouldInsertSpeechSpace(last, beforeLast, first) ? left + " " + right : left + right;
    }

    private boolean endsWithSubtitleBreak(String text) {
        return endsWithSubtitleSentenceBreak(text);
    }

    private boolean endsWithSubtitleSentenceBreak(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        int last = text.codePointBefore(text.length());
        return isSubtitleSentenceBreakChar(last);
    }

    private boolean isSubtitleBreakChar(int codePoint) {
        return isSubtitleSentenceBreakChar(codePoint);
    }

    private boolean isSubtitleSentenceBreakChar(int codePoint) {
        return "。！？；.!?;".indexOf(codePoint) >= 0;
    }

    private boolean isAsciiWordCodePoint(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '\'' || codePoint == '_' || codePoint == '+' || codePoint == '-';
    }

    private int subtitleWeight(String text) {
        return Math.max(1, subtitleDisplayWeight(text));
    }

    private int subtitleDisplayWeight(String text) {
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return 1;
        }
        int weight = 0;
        for (int i = 0; i < clean.length(); ) {
            int codePoint = clean.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            weight += script == Character.UnicodeScript.HAN ? 2 : 1;
        }
        return Math.max(1, weight);
    }

    private String formatAssTime(double seconds) {
        int centiseconds = (int) Math.max(0, Math.round(seconds * 100));
        int hours = centiseconds / 360000;
        centiseconds %= 360000;
        int minutes = centiseconds / 6000;
        centiseconds %= 6000;
        int secs = centiseconds / 100;
        int cs = centiseconds % 100;
        return String.format("%d:%02d:%02d.%02d", hours, minutes, secs, cs);
    }

    private String escapeAssText(String text) {
        String value = cleanSpeechText(text);
        if (!StringUtils.hasText(value)) {
            return "";
        }
        value = value.replace("\\", "\\\\")
                .replace("{", "｛")
                .replace("}", "｝")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\N");
        return wrapAssLine(value, 28);
    }

    private String wrapAssLine(String text, int lineLength) {
        String compact = cleanSpeechText(text);
        if (!StringUtils.hasText(compact) || subtitleDisplayWeight(compact) <= lineLength || compact.contains("\\N")) {
            return compact;
        }
        return String.join("\\N", splitLongSubtitleUnit(compact, lineLength));
    }

    private void burnAssSubtitle(Path videoFile, Path assFile, Path outputFile) {
        Path logFile = outputFile.getParent().resolve("ffmpeg-subtitle.log");
        try {
            SubtitleFont subtitleFont = resolveSubtitleFont();
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-vf", "ass=filename='" + escapeSubtitleFilterPath(assFile) + "'"
                            + subtitleFontsDirFilter(subtitleFont),
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "20",
                    "-c:a", "copy",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg 字幕烧录超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg 字幕烧录失败：" + trimPrompt(output, 500));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg 字幕烧录失败：" + e.getMessage());
        }
    }

    private Path applyHeadlineOverlayIfNeeded(CarSalesVideoDTO request, Path videoFile, Path tempDir, Long taskId) {
        CarSalesVideoDTO.TextOverlay overlay = request == null ? null : request.getHeadlineOverlay();
        if (overlay == null || !Boolean.TRUE.equals(overlay.getEnabled())) {
            return videoFile;
        }
        String text = cleanSpeechText(overlay.getText());
        if (!StringUtils.hasText(text)) {
            return videoFile;
        }
        String headlinePosition = normalizeOverlayPosition(overlay.getPosition(), "top");
        if (headlinePositionConflictsWithSubtitle(request, headlinePosition)) {
            overlay.setPosition(alternateHeadlinePosition(headlinePosition));
        } else {
            overlay.setPosition(headlinePosition);
        }
        int fontSize = normalizeHeadlineFontSize(overlay.getFontSize(), request);
        String wrappedText = wrapHeadlineOverlayText(text, fontSize, request);
        Path textFile = tempDir.resolve("car-sales-headline-" + taskId + ".txt");
        Path outputFile = tempDir.resolve("car-sales-final-" + taskId + "-with-headline.mp4");
        Path logFile = tempDir.resolve("ffmpeg-headline-overlay.log");
        try {
            Files.writeString(textFile, wrappedText, StandardCharsets.UTF_8);
            String filter = buildHeadlineDrawtextFilter(textFile, request, overlay, fontSize);
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFile.toString(),
                    "-vf", filter,
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "20",
                    "-c:a", "copy",
                    "-movflags", "+faststart",
                    outputFile.toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50100, "FFmpeg 大字报叠加超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                throw new BusinessException(50100, "FFmpeg 大字报叠加失败：" + trimPrompt(output, 500));
            }
            overlay.setText(text);
            overlay.setFontSize(fontSize);
            return outputFile;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "FFmpeg 大字报叠加失败：" + e.getMessage());
        }
    }

    private String buildHeadlineDrawtextFilter(Path textFile, CarSalesVideoDTO request,
                                               CarSalesVideoDTO.TextOverlay overlay, int fontSize) {
        StringBuilder filter = new StringBuilder("drawtext=");
        filter.append("textfile='").append(escapeSubtitleFilterPath(textFile)).append("'");
        SubtitleFont headlineFont = resolveSubtitleFont();
        if (headlineFont != null && headlineFont.fontFile() != null && Files.isRegularFile(headlineFont.fontFile())) {
            filter.append(":fontfile='").append(escapeSubtitleFilterPath(headlineFont.fontFile())).append("'");
        } else {
            String fontFamily = trimToNull(overlay == null ? null : overlay.getFontFamily());
            if (!StringUtils.hasText(fontFamily) && headlineFont != null) {
                fontFamily = trimToNull(headlineFont.fontName());
            }
            if (!StringUtils.hasText(fontFamily)) {
                fontFamily = DEFAULT_SUBTITLE_FONT_FAMILY;
            }
            filter.append(":font='").append(escapeFfmpegFilterValue(fontFamily)).append("'");
        }
        String textColor = normalizeFfmpegColor(overlay == null ? null : overlay.getTextColor(), "0xFFFFFF");
        String outlineColor = normalizeFfmpegColor(overlay == null ? null : overlay.getOutlineColor(), "0x111111");
        int borderWidth = Math.max(3, Math.min(10, Math.round(fontSize / 18.0f)));
        filter.append(":fontcolor=").append(textColor)
                .append(":fontsize=").append(fontSize)
                .append(":borderw=").append(borderWidth)
                .append(":bordercolor=").append(outlineColor)
                .append(":shadowcolor=black@0.35:shadowx=").append(Math.max(2, borderWidth / 2))
                .append(":shadowy=").append(Math.max(2, borderWidth / 2))
                .append(":line_spacing=").append(Math.max(4, fontSize / 10))
                .append(":fix_bounds=1")
                .append(":x=(w-text_w)/2")
                .append(":y=").append(headlineYExpression(request, overlay == null ? null : overlay.getPosition()));
        return filter.toString();
    }

    private int normalizeHeadlineFontSize(Integer value, CarSalesVideoDTO request) {
        boolean wide = isWideAspectRatio(request == null ? null : request.getAspectRatio());
        int fallback = wide ? 76 : 64;
        int max = wide ? 120 : 72;
        int min = wide ? 40 : 36;
        int size = value == null || value <= 0 ? fallback : value;
        return Math.max(min, Math.min(max, size));
    }

    private String wrapHeadlineOverlayText(String text, int fontSize, CarSalesVideoDTO request) {
        String clean = cleanSpeechText(text);
        if (!StringUtils.hasText(clean)) {
            return "";
        }
        int maxWeight = headlineSafeLineWeight(fontSize, request);
        List<String> lines = new ArrayList<>();
        for (String line : clean.split("\\n+")) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            lines.addAll(splitLongSubtitleUnit(trimmed, maxWeight));
        }
        return String.join("\n", lines);
    }

    private int headlineSafeLineWeight(int fontSize, CarSalesVideoDTO request) {
        boolean wide = isWideAspectRatio(request == null ? null : request.getAspectRatio());
        int canvasWidth = wide ? 1920 : 1080;
        double safeWidthRatio = wide ? 0.82 : 0.74;
        int weight = (int) Math.floor((canvasWidth * safeWidthRatio * 2.0d) / Math.max(1, fontSize));
        return wide
                ? Math.max(18, Math.min(42, weight))
                : Math.max(12, Math.min(24, weight));
    }

    private boolean isWideAspectRatio(String ratio) {
        return "16:9".equals(trimToDefault(ratio, ""));
    }

    private String headlineYExpression(CarSalesVideoDTO request, String position) {
        String value = normalizeOverlayPosition(position, "top");
        return switch (value) {
            case "middle", "center" -> "(h-text_h)/2";
            case "bottom" -> "h-text_h-h*0.12";
            default -> "h*0.07";
        };
    }

    private boolean headlinePositionConflictsWithSubtitle(CarSalesVideoDTO request, String headlinePosition) {
        return !isSubtitleDisabled(request)
                && normalizeOverlayPosition(headlinePosition, "top").equals(subtitlePosition(request));
    }

    private String alternateHeadlinePosition(String currentPosition) {
        return switch (normalizeOverlayPosition(currentPosition, "top")) {
            case "top" -> "bottom";
            case "middle" -> "top";
            default -> "top";
        };
    }

    private String normalizeFfmpegColor(String value, String fallback) {
        String clean = trimToNull(value);
        if (!StringUtils.hasText(clean)) {
            return fallback;
        }
        String hex = clean.startsWith("#") ? clean.substring(1) : clean;
        if (hex.matches("[0-9a-fA-F]{3}")) {
            hex = "" + hex.charAt(0) + hex.charAt(0)
                    + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return "0x" + hex.toUpperCase();
    }

    private String escapeFfmpegFilterValue(String value) {
        return value.replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    private String escapeSubtitleFilterPath(Path assFile) {
        return assFile.toAbsolutePath().toString()
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    private SubtitleFont resolveSubtitleFont() {
        List<Path> candidates = new ArrayList<>();
        if (StringUtils.hasText(subtitleFontFile)) {
            candidates.add(Path.of(subtitleFontFile));
        }
        candidates.addAll(List.of(
                Path.of("C:/Windows/Fonts/msyh.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
                Path.of("/usr/share/fonts/wenquanyi/wqy-microhei/wqy-microhei.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/source-han-sans/SourceHanSansSC-Regular.otf"),
                Path.of("/System/Library/Fonts/PingFang.ttc"),
                Path.of("C:/Windows/Fonts/NotoSansSC-VF.ttf"),
                Path.of("C:/Windows/Fonts/simhei.ttf"),
                Path.of("C:/Windows/Fonts/simsun.ttc")
        ));
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return new SubtitleFont(subtitleFontName(candidate), candidate.getParent(), candidate);
            }
        }
        return new SubtitleFont(DEFAULT_SUBTITLE_FONT_FAMILY, null, null);
    }

    private String subtitleFontName(Path fontFile) {
        String name = fontFile == null || fontFile.getFileName() == null
                ? "" : fontFile.getFileName().toString().toLowerCase();
        if (name.contains("noto")) {
            return name.contains("sanssc") ? "Noto Sans SC" : "Noto Sans CJK SC";
        }
        if (name.contains("sourcehan") || name.contains("source-han")) {
            return "Source Han Sans SC";
        }
        if (name.contains("wqy") || name.contains("wenquanyi")) {
            return "WenQuanYi Micro Hei";
        }
        if (name.contains("msyh") || name.contains("yahei")) {
            return "Microsoft YaHei";
        }
        if (name.contains("simhei")) {
            return "SimHei";
        }
        if (name.contains("simsun")) {
            return "SimSun";
        }
        if (name.contains("pingfang")) {
            return "PingFang SC";
        }
        return "Noto Sans CJK SC";
    }

    private String subtitleFontsDirFilter(SubtitleFont subtitleFont) {
        if (subtitleFont == null || subtitleFont.fontsDir() == null || !Files.isDirectory(subtitleFont.fontsDir())) {
            return "";
        }
        return ":fontsdir='" + escapeSubtitleFilterPath(subtitleFont.fontsDir()) + "'";
    }

    private record SubtitleFont(String fontName, Path fontsDir, Path fontFile) {
    }

    private record SrtCue(String start, String end, String text) {
    }

    private record SubtitleLayout(
            int playResX,
            int playResY,
            int assFontSize,
            int assMarginH,
            int assMarginV,
            int srtFontSize,
            int srtMarginV,
            int alignment,
            String primaryColour,
            String outlineColour,
            int assOutline,
            int srtOutline
    ) {
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
            String firstFrameUrl = extractAndUploadVideoFirstFrame(finalFile, "car-sales-video-" + task.taskId() + "-cover.jpg");
            String thumbnailUrl = firstNonBlank(
                    request == null ? null : request.getCoverUrl(),
                    firstFrameUrl,
                    resolveCarSalesRequestCoverUrl(request)
            );
            UploadResult stored = storageService.upload(in, Files.size(finalFile), fileName, "video/mp4", "video");
            return assetService.createGeneratedVideoAsset(
                    task.ownerUserId(),
                    task.projectId(),
                    task.taskId(),
                    stored.filename(),
                    stored.objectKey(),
                    stored.url(),
                    thumbnailUrl,
                    stored.contentType(),
                    stored.size(),
                    task.taskType(),
                    buildCarSalesFinalMetadata(task, request, model, inputJson, segmentVideos, segmentAssetIds,
                            totalDuration, totalTokens, thumbnailUrl, firstFrameUrl)
            );
        } catch (Exception e) {
            throw new BusinessException(50100, "保存汽车销售总片失败：" + e.getMessage());
        }
    }

    private String buildCarSalesFinalMetadata(TaskItem task, CarSalesVideoDTO request, String model, String inputJson,
                                              List<VideoTaskVO> segmentVideos, List<Long> segmentAssetIds,
                                              BigDecimal totalDuration, int totalTokens, String thumbnailUrl,
                                              String firstFrameUrl) {
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
        meta.put("voiceConsistencyMode", voiceConsistencyMode(request));
        meta.put("voicePolicy", request.getVoicePolicy());
        meta.put("finalVoiceText", request.getFinalVoiceText());
        meta.put("strictVoiceText", request.getStrictVoiceText());
        meta.put("generatedVoiceAssetId", request.getGeneratedVoiceAssetId());
        meta.put("generatedVoiceUrl", request.getGeneratedVoiceUrl());
        meta.put("autoTtsVoiceId", request.getAutoTtsVoiceId());
        meta.put("autoTtsSpeed", request.getAutoTtsSpeed());
        meta.put("autoTtsVolume", request.getAutoTtsVolume());
        meta.put("autoTtsPitch", request.getAutoTtsPitch());
        meta.put("nativeVoiceLanguage", request.getNativeVoiceLanguage());
        meta.put("nativeVoiceStyle", request.getNativeVoiceStyle());
        meta.put("nativeSpeechStyle", request.getNativeSpeechStyle());
        meta.put("bgmUrl", request.getBgmUrl());
        meta.put("subtitle", request.getSubtitle());
        meta.put("subtitleMode", request.getSubtitleMode());
        meta.put("subtitleLanguage", request.getSubtitleLanguage());
        meta.put("subtitleTimingMode", normalizeSubtitleTimingMode(request));
        meta.put("syncStrategy", normalizeSyncStrategy(request));
        meta.put("subtitleOverlay", request.getSubtitleOverlay());
        meta.put("headlineOverlay", request.getHeadlineOverlay());
        meta.put("ignoredStoryboardFields", request.getIgnoredStoryboardFields());
        meta.put("renderMode", request.getRenderMode());
        meta.put("aspectRatio", request.getAspectRatio());
        meta.put("quickAssetIds", request.getQuickAssetIds());
        meta.put("assetRoleBindings", request.getAssetRoleBindings());
        meta.put("coverAssetId", request.getCoverAssetId());
        meta.put("firstFrameUrl", firstNonBlank(firstFrameUrl, thumbnailUrl));
        meta.put("coverUrl", firstNonBlank(thumbnailUrl, request.getCoverUrl(), resolveCarSalesRequestCoverUrl(request)));
        meta.put("thumbnailUrl", firstNonBlank(thumbnailUrl, request.getCoverUrl(), resolveCarSalesRequestCoverUrl(request)));
        meta.put("materialCompleteness", buildCarMaterialCompleteness(request));
        appendCarSalesTestMetadata(meta, request);
        meta.put("referenceImageStrategy", isSeedance2(model)
                ? "按主体/场景/车辆角色选择最多 9 张相关参考图，场景图优先覆盖分镜地点"
                : "按片段角色选择 1 张首帧图");
        meta.put("customAudioApplied", shouldUseFinalAudio(request));
        meta.put("audioReferenceApplied", shouldReferenceAudio(request));
        meta.put("bgmApplied", StringUtils.hasText(request.getBgmUrl()));
        meta.put("hostImageUrl", request.getHostImageUrl());
        meta.put("hostAppearanceEnabled", hostAppearanceEnabled(request));
        meta.put("hostVideoUrl", request.getHostVideoUrl());
        meta.put("creationMode", request.getCreationMode());
        meta.put("chainType", request.getChainType());
        meta.put("videoType", request.getVideoType());
        meta.put("hasDigitalHuman", request.getHasDigitalHuman());
        meta.put("digitalHumanId", request.getDigitalHumanId());
        meta.put("voiceId", request.getVoiceId());
        meta.put("tone", request.getTone());
        meta.put("language", request.getLanguage());
        meta.put("duration", request.getDuration());
        meta.put("enableSubtitle", request.getEnableSubtitle());
        meta.put("subtitleStyle", request.getSubtitleStyle());
        meta.put("enableBigText", request.getEnableBigText());
        meta.put("bigTextStyle", request.getBigTextStyle());
        meta.put("enableBgm", request.getEnableBgm());
        meta.put("bgmStyle", request.getBgmStyle());
        meta.put("generateCover", request.getGenerateCover());
        meta.put("generateTitle", request.getGenerateTitle());
        meta.put("generateDescription", request.getGenerateDescription());
        meta.put("generateTags", request.getGenerateTags());
        meta.put("benchmarkVideoId", request.getBenchmarkVideoId());
        meta.put("uploadedVideoId", request.getUploadedVideoId());
        meta.put("reuseAssetIds", request.getReuseAssetIds());
        meta.put("vehicleId", request.getVehicleId());
        meta.put("vehicleName", request.getVehicleName());
        meta.put("taskMode", request.getTaskMode());
        meta.put("multiCarCompare", isMultiCarCompareRequest(request));
        meta.put("carPackages", request.getCarPackages());
        meta.put("input", parseJsonOrRaw(inputJson));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"provider\":\"VOLCENGINE\",\"source\":\"CAR_SALES_VIDEO\"}";
        }
    }

    private String resolveCarSalesRequestCoverUrl(CarSalesVideoDTO request) {
        if (request == null) {
            return null;
        }
        return firstNonBlank(
                request.getCoverUrl(),
                firstTextFromList(request.getCarImageUrls()),
                firstCoverFromScenes(request.getScenes())
        );
    }

    private String firstTextFromList(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String firstCoverFromScenes(List<CarSalesVideoDTO.Scene> scenes) {
        if (scenes == null) {
            return null;
        }
        for (CarSalesVideoDTO.Scene scene : scenes) {
            if (scene == null) {
                continue;
            }
            String url = firstNonBlank(scene.getReferenceImage(), firstTextFromList(scene.getImageUrls()));
            if (StringUtils.hasText(url)) {
                return url;
            }
        }
        return null;
    }

    private String extractAndUploadVideoFirstFrame(Path videoFile, String fileName) {
        if (videoFile == null || !Files.isRegularFile(videoFile)) {
            return null;
        }
        Path coverFile = videoFile.resolveSibling(fileName);
        Path logFile = videoFile.resolveSibling(fileName + ".log");
        try {
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-ss", "0",
                    "-i", videoFile.toAbsolutePath().toString(),
                    "-frames:v", "1",
                    "-q:v", "2",
                    coverFile.toAbsolutePath().toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("视频封面首帧抽取超时 video={}", videoFile);
                return null;
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(coverFile) || Files.size(coverFile) <= 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                log.warn("视频封面首帧抽取失败 video={} output={}", videoFile, abbreviateLog(output));
                return null;
            }
            try (InputStream in = Files.newInputStream(coverFile)) {
                UploadResult stored = storageService.upload(in, Files.size(coverFile), fileName, "image/jpeg", "cover");
                return stored.url();
            }
        } catch (Exception ex) {
            log.warn("视频封面首帧抽取跳过 video={} reason={}", videoFile, ex.getMessage());
            return null;
        } finally {
            try {
                Files.deleteIfExists(coverFile);
            } catch (Exception ignored) {
            }
            try {
                Files.deleteIfExists(logFile);
            } catch (Exception ignored) {
            }
        }
    }

    private String abbreviateLog(String output) {
        if (!StringUtils.hasText(output)) {
            return "";
        }
        String normalized = output.trim();
        return normalized.length() <= 900 ? normalized : normalized.substring(normalized.length() - 900);
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
        imageUrl.setUrl(seedanceResourceUrlValidator.resolveImageUrl(url));
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
        audioUrl.setUrl(seedanceResourceUrlValidator.resolveAudioUrl(url));
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
        return submitAndPoll(req, null);
    }

    private VideoTaskVO submitAndPoll(CreateContentGenerationTaskRequest req, PollObserver pollObserver) {
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

        long startedAt = System.currentTimeMillis();
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

            notifyPollObserver(pollObserver, taskId, task, System.currentTimeMillis() - startedAt, pollTimeoutMillis);

            if (System.currentTimeMillis() > deadline) {
                boolean cleanupAttempted = shouldCleanupTimedOutProviderTask(status);
                boolean cleanupSucceeded = cleanupAttempted && deleteTimedOutProviderTask(taskId, status);
                throw new BusinessException(50300,
                        "Seedance video generation polling timed out providerTaskId=" + taskId
                                + " lastStatus=" + status
                                + " pollTimeoutSeconds=" + TimeUnit.MILLISECONDS.toSeconds(pollTimeoutMillis)
                                + " providerCleanupAttempted=" + cleanupAttempted
                                + " providerCleanupSucceeded=" + cleanupSucceeded
                                + " providerCleanupSkippedReason=" + providerCleanupSkippedReason(status, cleanupAttempted));
            }
        }
    }

    private boolean shouldCleanupTimedOutProviderTask(String status) {
        return STATUS_QUEUED.equalsIgnoreCase(status);
    }

    private String providerCleanupSkippedReason(String status, boolean cleanupAttempted) {
        if (cleanupAttempted) {
            return "none";
        }
        if (STATUS_RUNNING.equalsIgnoreCase(status)) {
            return "running_task_deletion_not_supported";
        }
        if (!StringUtils.hasText(status)) {
            return "unknown_status";
        }
        return "terminal_or_unsupported_status";
    }

    private boolean deleteTimedOutProviderTask(String taskId, String status) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        try {
            arkService.deleteContentGenerationTask(DeleteContentGenerationTaskRequest.builder()
                    .taskId(taskId)
                    .build());
            log.warn("Seedance timed-out provider task delete requested taskId={} lastStatus={}", taskId, status);
            return true;
        } catch (Exception e) {
            log.warn("Seedance timed-out provider task delete failed taskId={} lastStatus={} reason={}",
                    taskId, status, e.getMessage());
            return false;
        }
    }

    private void notifyPollObserver(PollObserver pollObserver, String providerTaskId, VideoTaskVO task,
                                    long elapsedMillis, long timeoutMillis) {
        if (pollObserver == null) {
            return;
        }
        try {
            pollObserver.onPoll(providerTaskId, task, elapsedMillis, timeoutMillis);
        } catch (Exception e) {
            log.warn("Seedance poll observer ignored providerTaskId={} reason={}", providerTaskId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface PollObserver {
        void onPoll(String providerTaskId, VideoTaskVO task, long elapsedMillis, long timeoutMillis);
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
        if (isSeedanceResourceDownloadFailure(message)) {
            return "素材地址无法被视频模型下载，请使用资产中心/TOS 中可公网访问的图片或音频 URL";
        }
        Matcher matcher = ARK_REQUEST_ID_SUFFIX_PATTERN.matcher(message);
        if (matcher.find()) {
            return message.substring(0, matcher.start()).trim();
        }
        return message;
    }

    private boolean isSeedanceResourceDownloadFailure(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("resource download failed")
                && (lower.contains("image_url") || lower.contains("audio_url") || lower.contains("content["));
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
