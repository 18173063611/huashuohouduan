package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 视频生成服务实现：通过火山方舟 Ark Runtime SDK 调用 Seedance 系列模型创建异步视频生成任务。
 * 控制器调用本服务时为同步语义：内部创建任务后立即轮询「查询视频生成任务」接口，
 * 仅当任务 succeeded 且 videoUrl 非空时才返回；其他终态（failed / cancelled / expired / 超时）抛出业务异常。
 *
 * <p>统一接入本地任务台账：每次公开方法被调用时先 {@link TaskService#createTask} 写入本地 task 行并按
 * {@code ai_billing_step_config} 预扣积分，然后启动并保留原 Ark 调用 + 轮询逻辑；成功/失败/超时
 * 分别调用 {@link TaskService#completeTask} 与 {@link TaskService#failTask}（失败默认走自动退款）。</p>
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

    private static final String ENDPOINT_TEXT = "text";
    private static final String ENDPOINT_FIRST_FRAME = "first_frame";
    private static final String ENDPOINT_FIRST_LAST_FRAME = "first_last_frame";
    private static final String ENDPOINT_REFERENCE = "reference";

    private final ArkService arkService;
    private final String defaultModel;
    private final String referenceModel;
    private final long pollIntervalMillis;
    private final long pollTimeoutMillis;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;

    public VideoServiceImpl(
            ArkService seedanceArkService,
            @Value("${volcengine.seedance.model:doubao-seedance-1-5-pro}") String defaultModel,
            @Value("${volcengine.seedance.reference-model:doubao-seedance-1-0-lite-i2v-250428}") String referenceModel,
            @Value("${volcengine.seedance.poll-interval-seconds:5}") long pollIntervalSeconds,
            @Value("${volcengine.seedance.poll-timeout-seconds:600}") long pollTimeoutSeconds,
            TaskService taskService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService
    ) {
        this.arkService = seedanceArkService;
        this.defaultModel = defaultModel;
        this.referenceModel = referenceModel;
        this.pollIntervalMillis = Math.max(1L, pollIntervalSeconds) * 1000L;
        this.pollTimeoutMillis = Math.max(60L, pollTimeoutSeconds) * 1000L;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
    }

    @Override
    public VideoTaskVO generateText(TextDTO request, Long ownerUserId, Long projectId, String traceId,
                                    String idempotencyKey) {
        if (request == null || !StringUtils.hasText(request.getPrompt())) {
            throw new BusinessException(40000, "prompt 不能为空");
        }
        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : defaultModel;
        Long resolvedProjectId = firstNonNull(projectId, request.getProjectId());
        Map<String, Object> inputSnapshot = textInputSnapshot(request, model);
        String taskType = resolveTaskType(ENDPOINT_TEXT, model);
        return executeWithTask(taskType, model, resolvedProjectId, ownerUserId, traceId, idempotencyKey,
                inputSnapshot, request.getDuration(), () -> doGenerateText(request, model));
    }

    @Override
    public VideoTaskVO generateFirstFrame(ImageDTO request, Long ownerUserId, Long projectId, String traceId,
                                          String idempotencyKey) {
        if (request == null || !StringUtils.hasText(request.getImageUrl())) {
            throw new BusinessException(40000, "imageUrl 不能为空");
        }
        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : defaultModel;
        Long resolvedProjectId = firstNonNull(projectId, request.getProjectId());
        Map<String, Object> inputSnapshot = firstFrameInputSnapshot(request, model);
        String taskType = resolveTaskType(ENDPOINT_FIRST_FRAME, model);
        return executeWithTask(taskType, model, resolvedProjectId, ownerUserId, traceId, idempotencyKey,
                inputSnapshot, request.getDuration(), () -> doGenerateFirstFrame(request, model));
    }

    @Override
    public VideoTaskVO generateFirstLastFrame(ImageFirstLastFrameDTO request, Long ownerUserId, Long projectId,
                                              String traceId, String idempotencyKey) {
        if (request == null
                || !StringUtils.hasText(request.getFirstFrameUrl())
                || !StringUtils.hasText(request.getLastFrameUrl())) {
            throw new BusinessException(40000, "firstFrameUrl 与 lastFrameUrl 均不能为空");
        }
        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : defaultModel;
        Long resolvedProjectId = firstNonNull(projectId, request.getProjectId());
        Map<String, Object> inputSnapshot = firstLastFrameInputSnapshot(request, model);
        String taskType = resolveTaskType(ENDPOINT_FIRST_LAST_FRAME, model);
        return executeWithTask(taskType, model, resolvedProjectId, ownerUserId, traceId, idempotencyKey,
                inputSnapshot, request.getDuration(), () -> doGenerateFirstLastFrame(request, model));
    }

    @Override
    public VideoTaskVO generateReference(ImageReferenceDTO request, Long ownerUserId, Long projectId, String traceId,
                                         String idempotencyKey) {
        if (request == null || request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            throw new BusinessException(40000, "imageUrls 不能为空");
        }
        String model = StringUtils.hasText(request.getModel()) ? request.getModel() : referenceModel;
        Long resolvedProjectId = firstNonNull(projectId, request.getProjectId());
        Map<String, Object> inputSnapshot = referenceInputSnapshot(request, model);
        String taskType = resolveTaskType(ENDPOINT_REFERENCE, model);
        return executeWithTask(taskType, model, resolvedProjectId, ownerUserId, traceId, idempotencyKey,
                inputSnapshot, request.getDuration(), () -> doGenerateReference(request, model));
    }

    // ---- 任务台账包装：先 createTask 预扣积分 -> 调用 Ark -> 成功 completeTask / 失败 failTask（退款） ----

    private VideoTaskVO executeWithTask(String taskType, String modelCode, Long projectId, Long ownerUserId,
                                        String traceId, String idempotencyKey,
                                        Map<String, Object> inputSnapshot, Integer requestedDurationSeconds,
                                        ArkExecution execution) {
        String inputJson = toJson(inputSnapshot);
        TaskItem taskItem;
        try {
            taskItem = taskService.createTask(projectId, taskType, inputJson, traceId, ownerUserId,
                    modelCode, null, normalizeKey(idempotencyKey));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            log.error("Seedance 创建本地任务失败 taskType={} model={}", taskType, modelCode, ex);
            throw new BusinessException(50000, "视频任务创建失败：" + ex.getMessage());
        }

        Long localTaskId = taskItem.taskId();
        if (localTaskId == null) {
            // 极端兜底（不应到达）：未拿到任务 ID 时按旧行为直接调用 Ark，保证不阻塞业务。
            return execution.execute();
        }
        try {
            taskService.startTask(localTaskId);
        } catch (Exception startEx) {
            log.warn("Seedance startTask 失败，跳过状态置 RUNNING taskId={} reason={}", localTaskId, startEx.getMessage());
        }

        VideoTaskVO arkResult;
        try {
            arkResult = execution.execute();
        } catch (BusinessException be) {
            safelyFailTask(localTaskId, be.getMessage(), true);
            throw be;
        } catch (Exception ex) {
            log.error("Seedance Ark 调用异常 localTaskId={}", localTaskId, ex);
            safelyFailTask(localTaskId, ex.getMessage(), true);
            throw new BusinessException(50100, "视频生成失败：" + ex.getMessage());
        }

        if (arkResult == null) {
            safelyFailTask(localTaskId, "Ark 未返回任务结果", true);
            throw new BusinessException(50100, "视频生成失败：Ark 未返回任务结果");
        }
        arkResult.setLocalTaskId(localTaskId);
        // 尽力补全 durationSeconds：Ark SDK 当前未返回真实视频时长，先用用户请求的 duration 兜底（仅用于落账）。
        BigDecimal resolvedDuration = resolveDurationSeconds(arkResult, requestedDurationSeconds);
        if (resolvedDuration != null) {
            arkResult.setDurationSeconds(resolvedDuration);
        }

        try {
            taskService.completeTask(localTaskId, toJson(taskOutputSnapshot(arkResult)));
        } catch (Exception completeEx) {
            log.warn("Seedance completeTask 失败 localTaskId={} reason={}", localTaskId, completeEx.getMessage());
        }

        // 真实用量回写：拿到 durationSeconds(>0) 走完整 settle 走积分对账；否则仅写 ACTUAL 占位行，不补扣 / 退款。
        recordOrSettleActual(localTaskId, arkResult, modelCode, resolvedDuration);
        return arkResult;
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
     * 任意异常都不影响业务返回 - 仅打 warn 日志，避免覆盖 Seedance 成功结果。
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

        // 拿不到真实视频时长：仅写 ACTUAL 占位记录 + 同步 task.modelCode/providerTaskId，不走积分变动。
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

    private void safelyFailTask(Long localTaskId, String message, boolean refund) {
        if (localTaskId == null) {
            return;
        }
        try {
            taskService.failTask(localTaskId, abbreviate(message, 480), false, refund);
        } catch (Exception ignored) {
            log.warn("Seedance failTask 调用失败 localTaskId={} reason={}", localTaskId, ignored.getMessage());
        }
    }

    private Map<String, Object> taskOutputSnapshot(VideoTaskVO ark) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arkTaskId", ark.getTaskId());
        out.put("providerTaskId", ark.getTaskId());
        out.put("model", ark.getModel());
        out.put("status", ark.getStatus());
        out.put("videoUrl", ark.getVideoUrl());
        out.put("lastFrameUrl", ark.getLastFrameUrl());
        out.put("completionTokens", ark.getCompletionTokens());
        out.put("durationSeconds", ark.getDurationSeconds());
        return out;
    }

    // ---- 原 Ark 调用逻辑（保持不变，仅拆分以便包装事务/积分） ----

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

            // succeeded 且 videoUrl 已就绪才视为最终成功（满足用户要求：仅 videoUrl 非空时返回）。
            if (STATUS_SUCCEEDED.equalsIgnoreCase(status) && StringUtils.hasText(task.getVideoUrl())) {
                return task;
            }

            // 终态失败：抛出业务异常给上层。
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

        // 仅 status=succeeded 时 content 才会带 videoUrl；其他状态可能为 null。
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

    // ---- task_type 推导 + input/output 快照 ----

    /**
     * 根据请求端点与模型名挑选与 {@code ai_billing_step_config} 对齐的 task_type：
     * <ul>
     *   <li>模型名含 {@code seedance-2-0-fast}：参考图 -> {@code IMAGE_TO_VIDEO_SEEDANCE_2_0_FAST}</li>
     *   <li>模型名含 {@code seedance-2-0}：文生 -> {@code TEXT_TO_VIDEO_SEEDANCE_2_0}，图生 -> {@code IMAGE_TO_VIDEO_SEEDANCE_2_0}</li>
     *   <li>其余（含 1.5 pro 与 1.0 lite i2v 参考图回退）：文生 -> 1.5，图生 -> 1.5</li>
     * </ul>
     */
    private String resolveTaskType(String endpoint, String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        boolean v20Fast = m.contains("seedance-2-0-fast");
        boolean v20 = m.contains("seedance-2-0");
        if (ENDPOINT_TEXT.equals(endpoint)) {
            return v20 ? TaskTypeCode.TEXT_TO_VIDEO_SEEDANCE_2_0 : TaskTypeCode.TEXT_TO_VIDEO_SEEDANCE_1_5;
        }
        if (ENDPOINT_REFERENCE.equals(endpoint)) {
            if (v20Fast) {
                return TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_2_0_FAST;
            }
            if (v20) {
                return TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_2_0;
            }
            return TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_1_5;
        }
        // first_frame / first_last_frame
        return v20 ? TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_2_0 : TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_1_5;
    }

    private Map<String, Object> textInputSnapshot(TextDTO request, String model) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("endpoint", ENDPOINT_TEXT);
        input.put("model", model);
        input.put("projectId", request.getProjectId());
        input.put("prompt", request.getPrompt());
        input.put("resolution", request.getResolution());
        input.put("ratio", request.getRatio());
        input.put("duration", request.getDuration());
        input.put("seed", request.getSeed());
        input.put("cameraFixed", request.getCameraFixed());
        input.put("watermark", request.getWatermark());
        input.put("generateAudio", request.getGenerateAudio());
        input.put("callbackUrl", request.getCallbackUrl());
        input.put("safetyIdentifier", request.getSafetyIdentifier());
        return input;
    }

    private Map<String, Object> firstFrameInputSnapshot(ImageDTO request, String model) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("endpoint", ENDPOINT_FIRST_FRAME);
        input.put("model", model);
        input.put("projectId", request.getProjectId());
        input.put("imageUrl", request.getImageUrl());
        input.put("prompt", request.getPrompt());
        input.put("resolution", request.getResolution());
        input.put("ratio", request.getRatio());
        input.put("duration", request.getDuration());
        input.put("seed", request.getSeed());
        input.put("cameraFixed", request.getCameraFixed());
        input.put("watermark", request.getWatermark());
        input.put("generateAudio", request.getGenerateAudio());
        input.put("callbackUrl", request.getCallbackUrl());
        input.put("safetyIdentifier", request.getSafetyIdentifier());
        return input;
    }

    private Map<String, Object> firstLastFrameInputSnapshot(ImageFirstLastFrameDTO request, String model) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("endpoint", ENDPOINT_FIRST_LAST_FRAME);
        input.put("model", model);
        input.put("projectId", request.getProjectId());
        input.put("firstFrameUrl", request.getFirstFrameUrl());
        input.put("lastFrameUrl", request.getLastFrameUrl());
        input.put("prompt", request.getPrompt());
        input.put("resolution", request.getResolution());
        input.put("ratio", request.getRatio());
        input.put("duration", request.getDuration());
        input.put("seed", request.getSeed());
        input.put("cameraFixed", request.getCameraFixed());
        input.put("watermark", request.getWatermark());
        input.put("generateAudio", request.getGenerateAudio());
        input.put("callbackUrl", request.getCallbackUrl());
        input.put("safetyIdentifier", request.getSafetyIdentifier());
        return input;
    }

    private Map<String, Object> referenceInputSnapshot(ImageReferenceDTO request, String model) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("endpoint", ENDPOINT_REFERENCE);
        input.put("model", model);
        input.put("projectId", request.getProjectId());
        input.put("imageUrls", request.getImageUrls());
        input.put("prompt", request.getPrompt());
        input.put("resolution", request.getResolution());
        input.put("ratio", request.getRatio());
        input.put("duration", request.getDuration());
        input.put("seed", request.getSeed());
        input.put("watermark", request.getWatermark());
        input.put("generateAudio", request.getGenerateAudio());
        input.put("callbackUrl", request.getCallbackUrl());
        input.put("safetyIdentifier", request.getSafetyIdentifier());
        return input;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Seedance 任务参数序列化失败 reason={}", e.getMessage());
            return null;
        }
    }

    private Long firstNonNull(Long a, Long b) {
        return a != null ? a : b;
    }

    private String normalizeKey(String idempotencyKey) {
        return StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
    }

    private String abbreviate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    @FunctionalInterface
    private interface ArkExecution {
        VideoTaskVO execute();
    }
}
