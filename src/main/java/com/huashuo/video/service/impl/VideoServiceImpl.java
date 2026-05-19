package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.service.VideoService;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final String ROLE_FIRST_FRAME = "first_frame";
    private static final String ROLE_LAST_FRAME = "last_frame";
    private static final String ROLE_REFERENCE_IMAGE = "reference_image";

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_EXPIRED = "expired";

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
            AssetService assetService
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
        AssetItem resultAsset = saveSeedanceVideoAsset(task, arkResult, resolvedModel, inputJson);
        if (resultAsset != null) {
            arkResult.setVideoUrl(resultAsset.fileUrl());
            arkResult.setResultAssetId(resultAsset.assetId());
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
                .generateAudio(true)
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
                .generateAudio(true)
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
                .generateAudio(true)
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
        // 参照图场景不支持 cameraFixed，故不传该字段（保留原默认 false 由 baseBuilder 兜底）。
        CreateContentGenerationTaskRequest req = baseBuilder(model, contents)
                .duration(toLong(request.getDuration()))
                .seed(toLong(-1))
                .cameraFixed(false)
                .watermark(false)
                .generateAudio(true)
                .build();
        return submitAndPoll(req);
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
            throw new BusinessException(50100, "视频生成任务创建失败：" + e.getMessage());
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
