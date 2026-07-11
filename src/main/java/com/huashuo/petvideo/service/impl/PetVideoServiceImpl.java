package com.huashuo.petvideo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.common.ai.ArkChatResult;
import com.huashuo.common.ai.ArkTextClient;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import com.huashuo.petvideo.dto.PetWorkDownloadResponse;
import com.huashuo.petvideo.dto.PetWorkForkRequest;
import com.huashuo.petvideo.dto.PetVideoEstimateResponse;
import com.huashuo.petvideo.dto.PetVideoPreviewResponse;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.dto.PetWorkResponse;
import com.huashuo.petvideo.entity.PetVideoWorkEntity;
import com.huashuo.petvideo.mapper.PetVideoWorkMapper;
import com.huashuo.petvideo.service.PetVideoService;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.service.VideoAsyncTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

@Service
public class PetVideoServiceImpl implements PetVideoService {

    private static final Logger log = LoggerFactory.getLogger(PetVideoServiceImpl.class);
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final Set<String> ASPECT_RATIOS = Set.of("9:16", "16:9", "1:1");
    private static final int MIN_DURATION_SECONDS = 4;
    private static final int MAX_DURATION_SECONDS = 15;
    private static final String STICKER_OVERLAY_FILE_MARKER = "pet-sticker-overlay-";
    private static final Set<String> DIALOGUE_EMOTIONS = Set.of("委屈", "开心", "吐槽", "认真解释", "撒娇", "惊讶");
    private static final Set<String> DIALOGUE_SPEEDS = Set.of("slow", "normal", "fast");
    private static final Duration PET_SCRIPT_AI_TIMEOUT = Duration.ofSeconds(22);

    private final PetVideoWorkMapper workMapper;
    private final VideoAsyncTaskService videoAsyncTaskService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final PetCreationDraftValidator draftValidator;
    private final PetVideoPromptBuilder promptBuilder;
    private final BillingEstimateService billingEstimateService;
    private final PetVideoProperties petVideoProperties;
    private final ArkTextClient arkTextClient;
    private final StorageService storageService;
    private final String petScriptModel;
    private final String seedanceModelCode;
    private final String seedanceReferenceModelCode;

    public PetVideoServiceImpl(PetVideoWorkMapper workMapper,
                               VideoAsyncTaskService videoAsyncTaskService,
                               TaskService taskService,
                               ObjectMapper objectMapper,
                               PetCreationDraftValidator draftValidator,
                               PetVideoPromptBuilder promptBuilder,
                               BillingEstimateService billingEstimateService,
                               PetVideoProperties petVideoProperties,
                               ArkTextClient arkTextClient,
                               StorageService storageService,
                               @Value("${volcengine.ark.pet-script-model:${VOLCENGINE_ARK_PET_SCRIPT_MODEL:doubao-seed-2-0-pro-260215}}") String petScriptModel,
                               @Value("${volcengine.seedance.model:}") String seedanceModelCode,
                               @Value("${volcengine.seedance.reference-model:${volcengine.seedance.model:}}") String seedanceReferenceModelCode) {
        this.workMapper = workMapper;
        this.videoAsyncTaskService = videoAsyncTaskService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.draftValidator = draftValidator;
        this.promptBuilder = promptBuilder;
        this.billingEstimateService = billingEstimateService;
        this.petVideoProperties = petVideoProperties;
        this.arkTextClient = arkTextClient;
        this.storageService = storageService;
        this.petScriptModel = StringUtils.hasText(petScriptModel) ? petScriptModel.trim() : "doubao-seed-2-0-pro-260215";
        this.seedanceModelCode = seedanceModelCode;
        this.seedanceReferenceModelCode = seedanceReferenceModelCode;
    }

    @Override
    public JsonNode generateScript(JsonNode draft, Long ownerUserId) {
        ObjectNode next = normalizedDraft(draft);
        draftValidator.validateForScript(next);
        String prompt = requireTextPrompt(next);
        applyGeneratedScript(next, prompt, true);
        return next;
    }

    @Override
    public JsonNode generateStoryboard(JsonNode draft, Long ownerUserId) {
        ObjectNode next = normalizedDraft(draft);
        draftValidator.validateForScript(next);
        String prompt = requireTextPrompt(next);
        applyGeneratedScript(next, prompt, false);
        draftValidator.validateForStoryboard(next);
        ArrayNode adaptiveShots = buildAdaptiveStoryboardShots(next);
        if (!adaptiveShots.isEmpty()) {
            next.set("shots", adaptiveShots);
            return next;
        }
        ArrayNode shots = objectMapper.createArrayNode();
        String backgroundPrompt = text(next.get("visualSettings"), "backgroundPrompt");
        String stylePrompt = text(next.get("visualSettings"), "stylePrompt");
        String[] frames = {
                StringUtils.hasText(backgroundPrompt)
                        ? "主宠出现在" + backgroundPrompt + "中，保持外貌和毛色一致，建立场景氛围"
                        : "主宠出现在画面中心，保持外貌和毛色一致，建立场景氛围",
                "主宠做出明确动作或表情，第二只宠物/道具按设定参与互动",
                "用近景强化萌点、冲突点或口播重点，字幕节奏清晰",
                "镜头收束到主宠反应或故事反转，保留可二创的结尾"
        };
        int totalDuration = duration(next);
        int baseShotDuration = Math.max(1, totalDuration / frames.length);
        int remainingSeconds = Math.max(0, totalDuration - baseShotDuration * frames.length);
        for (int i = 0; i < frames.length; i++) {
            ObjectNode shot = objectMapper.createObjectNode();
            shot.put("id", "shot-" + (i + 1));
            shot.put("index", i + 1);
            shot.put("durationSeconds", baseShotDuration + (i < remainingSeconds ? 1 : 0));
            shot.put("frameDescription", i == 0 && StringUtils.hasText(stylePrompt)
                    ? frames[i] + "；风格描述：" + limit(stylePrompt, 120)
                    : frames[i]);
            shot.put("characterAction", i == 0 ? "进入镜头并看向观众" : "按照脚本完成表情和动作衔接");
            shot.put("cameraMove", i % 2 == 0 ? "稳定推近" : "轻微跟拍");
            shot.put("subtitle", shotSubtitle(next, i));
            shot.put("voiceEmotion", i == frames.length - 1 ? "惊讶" : "委屈");
            shots.add(shot);
        }
        next.set("shots", shots);
        return next;
    }

    @Override
    public PetVideoEstimateResponse estimate(JsonNode draft, Long ownerUserId) {
        ObjectNode normalizedDraft = normalizedDraft(draft);
        draftValidator.validateForTask(normalizedDraft);
        String taskType = taskTypeForDraft(normalizedDraft);
        long creditCost = creditCostForDraft(normalizedDraft, taskType);
        BillingEstimateResponse billing = estimateBilling(normalizedDraft, ownerUserId, taskType, creditCost);
        long estimatedCredits = effectiveEstimatedCredits(creditCost, billing);
        return new PetVideoEstimateResponse(
                taskType,
                draftValidator.resolveGenerationMode(normalizedDraft),
                estimatedCredits,
                billing.balance(),
                billing.balance() == null ? null : billing.balance() >= estimatedCredits,
                "PET_DYNAMIC_ESTIMATE",
                array(normalizedDraft, "materials").size(),
                array(normalizedDraft, "shots").size(),
                draftValidator.warnings(normalizedDraft)
        );
    }

    @Override
    public PetVideoPreviewResponse previewTask(JsonNode draft, Long ownerUserId) {
        if (!petVideoProperties.isDryRunEnabled()) {
            throw new BusinessException(40300, "PET_DRY_RUN_DISABLED: 当前环境未开启宠物视频 dry-run 预检。");
        }
        ObjectNode normalizedDraft = normalizedDraft(draft);
        draftValidator.validateForTask(normalizedDraft);
        stampPromptDiagnostics(normalizedDraft);
        String taskType = taskTypeForDraft(normalizedDraft);
        long creditCost = creditCostForDraft(normalizedDraft, taskType);
        BillingEstimateResponse billing = estimateBilling(normalizedDraft, ownerUserId, taskType, creditCost);
        String mode = draftValidator.resolveGenerationMode(normalizedDraft);
        List<String> imageUrls = PetCreationDraftValidator.MODE_TEXT_VIDEO.equals(mode)
                ? List.of()
                : referenceImageUrls(normalizedDraft);
        List<String> audioUrls = referenceAudioUrls(normalizedDraft);
        long estimatedCredits = effectiveEstimatedCredits(creditCost, billing);
        ObjectNode payloadPreview = buildProviderPayloadPreview(normalizedDraft, imageUrls, audioUrls, estimatedCredits);
        Boolean enoughBalance = billing == null || billing.balance() == null
                ? null
                : billing.balance() >= estimatedCredits;
        boolean wouldCreateTask = !Boolean.FALSE.equals(enoughBalance);
        String message = petVideoProperties.isProviderSubmitEnabled()
                ? "Dry-run 已通过；如继续提交，将调用第三方视频生成并可能产生费用。"
                : "当前处于本地安全测试模式，未调用第三方视频生成。";
        return new PetVideoPreviewResponse(
                true,
                petVideoProperties.isProviderSubmitEnabled(),
                petVideoProperties.isDryRunEnabled(),
                false,
                false,
                wouldCreateTask,
                petVideoProperties.isProviderSubmitEnabled() ? null : "PROVIDER_SUBMIT_DISABLED",
                message,
                taskType,
                mode,
                estimatedCredits,
                billing == null ? null : billing.balance(),
                enoughBalance,
                "PET_DYNAMIC_ESTIMATE",
                modelCodeFor(imageUrls),
                duration(normalizedDraft),
                aspectRatio(normalizedDraft),
                text(normalizedDraft, "style", "cute"),
                text(normalizedDraft, "language", "zh-CN"),
                promptBuilder.build(normalizedDraft),
                promptBuilder.negativePrompt(),
                payloadPreview,
                materialSummary(normalizedDraft),
                shotSummary(normalizedDraft),
                draftValidator.warnings(normalizedDraft)
        );
    }

    @Override
    @Transactional
    public PetVideoTaskResponse createTask(JsonNode draft, Long ownerUserId, String traceId, String idempotencyKey) {
        ObjectNode normalizedDraft = normalizedDraft(draft);
        draftValidator.validateForTask(normalizedDraft);
        if (!petVideoProperties.isProviderSubmitEnabled()) {
            throw new BusinessException(50300, "PROVIDER_SUBMIT_DISABLED: 当前处于本地安全测试模式，未调用第三方视频生成。请先调用 /pet-videos/tasks/preview 完成 dry-run，并显式开启 HUASHUO_PET_VIDEO_PROVIDER_SUBMIT_ENABLED=true 后再创建真实任务。");
        }
        stampPromptDiagnostics(normalizedDraft);
        String title = titleOf(normalizedDraft);
        TaskItem task = createSeedanceTask(normalizedDraft, ownerUserId, traceId, idempotencyKey);
        PetVideoWorkEntity work = findByTaskId(task.taskId(), ownerUserId);
        if (work == null) {
            work = new PetVideoWorkEntity();
            work.setOwnerUserId(ownerUserId);
            work.setTaskId(task.taskId());
            work.setSourceWorkId(null);
            work.setTitle(title);
            work.setStatus(STATUS_RUNNING);
            work.setPetType(petTypeOf(normalizedDraft));
            work.setAspectRatio(aspectRatio(normalizedDraft));
            work.setDurationSeconds(duration(normalizedDraft));
            work.setDraftJson(toJson(normalizedDraft));
            work.setCreatedAt(LocalDateTime.now());
            work.setUpdatedAt(LocalDateTime.now());
            work.setDeleted(0);
            workMapper.insert(work);
        }
        return toTaskResponse(task, work);
    }

    @Override
    public PetVideoTaskResponse getTask(Long taskId, Long ownerUserId) {
        if (taskId == null) {
            throw new BusinessException(40000, "taskId is required");
        }
        TaskItem task = taskService.getTaskForViewer(taskId, OptionalLong.of(ownerUserId));
        PetVideoWorkEntity work = findByTaskId(task.taskId(), ownerUserId);
        if (work == null) {
            throw new BusinessException(40400, "Pet video task not found");
        }
        return toTaskResponse(task, work);
    }

    @Override
    public List<PetWorkResponse> listWorks(Long ownerUserId, String status, String keyword, String petType) {
        LambdaQueryWrapper<PetVideoWorkEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PetVideoWorkEntity::getOwnerUserId, ownerUserId)
                .eq(PetVideoWorkEntity::getDeleted, 0)
                .orderByDesc(PetVideoWorkEntity::getCreatedAt)
                .last("limit 100");
        List<PetVideoWorkEntity> rows = workMapper.selectList(wrapper);
        String normalizedStatus = normalizeFilter(status);
        String normalizedPetType = normalizePetTypeFilter(petType);
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        return rows.stream()
                .map(row -> toWorkResponse(row, ownerUserId))
                .filter(work -> normalizedStatus == null || normalizedStatus.equals(work.status()))
                .filter(work -> normalizedPetType == null || normalizedPetType.equals(work.petType()))
                .filter(work -> normalizedKeyword == null
                        || work.title().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || text(work.draft(), "prompt").toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .toList();
    }

    @Override
    @Transactional
    public PetWorkResponse forkWork(Long workId, PetWorkForkRequest request, Long ownerUserId) {
        PetVideoWorkEntity source = requireWork(workId, ownerUserId);
        ObjectNode forkDraft = normalizedDraft(readDraft(source));
        String ratio = request == null ? null : request.aspectRatio();
        if (ASPECT_RATIOS.contains(ratio)) {
            forkDraft.put("aspectRatio", ratio);
        }
        PetVideoWorkEntity created = new PetVideoWorkEntity();
        created.setOwnerUserId(ownerUserId);
        created.setTaskId(null);
        created.setSourceWorkId(source.getWorkId());
        created.setTitle(limit(source.getTitle() + " 副本", 160));
        created.setStatus(STATUS_DRAFT);
        created.setPetType(petTypeOf(forkDraft));
        created.setAspectRatio(aspectRatio(forkDraft));
        created.setDurationSeconds(duration(forkDraft));
        created.setDraftJson(toJson(forkDraft));
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        created.setDeleted(0);
        workMapper.insert(created);
        return toWorkResponse(created, ownerUserId);
    }

    @Override
    @Transactional
    public PetVideoTaskResponse regenerateWork(Long workId, Long ownerUserId, String traceId, String idempotencyKey) {
        PetVideoWorkEntity source = requireWork(workId, ownerUserId);
        return createTask(readDraft(source), ownerUserId, traceId, idempotencyKey);
    }

    @Override
    @Transactional
    public void deleteWork(Long workId, Long ownerUserId) {
        PetVideoWorkEntity work = requireWork(workId, ownerUserId);
        work.setDeleted(1);
        work.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(work);
    }

    @Override
    public PetWorkDownloadResponse downloadWork(Long workId, Long ownerUserId) {
        PetWorkResponse work = toWorkResponse(requireWork(workId, ownerUserId), ownerUserId);
        String videoUrl = work.videoUrl();
        String fileName = "pet-video-" + work.id() + ".mp4";
        if (!StringUtils.hasText(videoUrl)) {
            return new PetWorkDownloadResponse(fileName, null, "", "video/mp4");
        }
        return new PetWorkDownloadResponse(fileName, videoUrl, "", "video/mp4");
    }

    private TaskItem createSeedanceTask(ObjectNode draft, Long ownerUserId, String traceId, String idempotencyKey) {
        String prompt = promptBuilder.build(draft);
        String ratio = aspectRatio(draft);
        int duration = duration(draft);
        String mode = draftValidator.resolveGenerationMode(draft);
        List<String> imageUrls = PetCreationDraftValidator.MODE_TEXT_VIDEO.equals(mode)
                ? List.of()
                : referenceImageUrls(draft);
        String normalizedIdempotencyKey = normalizeIdempotency(idempotencyKey);
        String taskType = imageUrls.isEmpty() ? TaskTypeCode.SEEDANCE_TEXT_VIDEO : TaskTypeCode.SEEDANCE_REFERENCE_VIDEO;
        long creditCost = creditCostForDraft(draft, taskType);
        creditCost = effectiveEstimatedCredits(creditCost, estimateBilling(draft, ownerUserId, taskType, creditCost));
        JsonNode diagnosticMetadata = buildTaskDiagnosticMetadata(draft, imageUrls, creditCost);
        if (!imageUrls.isEmpty()) {
            ImageReferenceDTO request = new ImageReferenceDTO();
            request.setImageUrls(imageUrls);
            request.setAudioUrls(referenceAudioUrls(draft));
            request.setPrompt(prompt);
            request.setRatio(ratio);
            request.setDuration(duration);
            request.setWatermark(false);
            request.setGenerateAudio(booleanValue(draft, "voiceEnabled"));
            request.setSafetyIdentifier("pet-creation-" + ownerUserId);
            request.setBusinessType("pet_creation");
            request.setDiagnosticMetadata(diagnosticMetadata);
            return videoAsyncTaskService.createReferenceVideoTask(
                    request,
                    traceId,
                    ownerUserId,
                    null,
                    normalizedIdempotencyKey,
                    creditCost
            );
        }
        TextDTO request = new TextDTO();
        request.setPrompt(prompt);
        request.setRatio(ratio);
        request.setDuration(duration);
        request.setWatermark(false);
        request.setGenerateAudio(booleanValue(draft, "voiceEnabled"));
        request.setSafetyIdentifier("pet-creation-" + ownerUserId);
        request.setBusinessType("pet_creation");
        request.setDiagnosticMetadata(diagnosticMetadata);
        return videoAsyncTaskService.createTextVideoTask(request, traceId, ownerUserId, null, normalizedIdempotencyKey, creditCost);
    }

    private String taskTypeForDraft(JsonNode draft) {
        String mode = draftValidator.resolveGenerationMode(draft);
        return PetCreationDraftValidator.MODE_TEXT_VIDEO.equals(mode)
                ? TaskTypeCode.SEEDANCE_TEXT_VIDEO
                : TaskTypeCode.SEEDANCE_REFERENCE_VIDEO;
    }

    private long creditCostForDraft(JsonNode draft, String taskType) {
        long base = billingEstimateService.resolveCreditCost(taskType, null);
        if (base <= 0) {
            base = TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType) ? 200L : 220L;
        }
        long durationExtra = duration(draft) > 15 ? 80L : 0L;
        long materialExtra = Math.max(0, draftValidator.countReferenceImages(draft) - 2) * 10L;
        long voiceExtra = booleanValue(draft, "voiceEnabled") ? 20L : 0L;
        long lipSyncExtra = booleanValue(draft, "lipSyncEnabled") ? 20L : 0L;
        return Math.max(0L, base + durationExtra + materialExtra + voiceExtra + lipSyncExtra);
    }

    private long effectiveEstimatedCredits(long petEstimatedCredits, BillingEstimateResponse billing) {
        long billingEstimatedCredits = billing == null ? 0L : Math.max(0L, billing.estimatedCreditCost());
        return Math.max(Math.max(0L, petEstimatedCredits), billingEstimatedCredits);
    }

    private BillingEstimateResponse estimateBilling(JsonNode draft, Long ownerUserId, String taskType, long creditCost) {
        return billingEstimateService.estimate(new BillingEstimateRequest(
                taskType,
                null,
                null,
                text(draft, "prompt").length(),
                Math.max(1, draftValidator.countReferenceImages(draft)),
                shotCount(draft),
                java.math.BigDecimal.valueOf(duration(draft)),
                java.math.BigDecimal.valueOf(creditCost),
                ownerUserId
        ));
    }

    private int shotCount(JsonNode draft) {
        return array(draft, "shots").size();
    }

    private void stampPromptDiagnostics(ObjectNode draft) {
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("promptVersion", promptBuilder.promptVersion());
        diagnostics.put("styleVersion", promptBuilder.styleVersion());
        diagnostics.put("generationMode", draftValidator.resolveGenerationMode(draft));
        diagnostics.put("materialCount", array(draft, "materials").size());
        diagnostics.put("shotCount", array(draft, "shots").size());
        draft.set("diagnostics", diagnostics);
    }

    private JsonNode buildTaskDiagnosticMetadata(ObjectNode draft, List<String> imageUrls, long creditCost) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("businessType", "pet_creation");
        metadata.put("businessDomain", "pet");
        metadata.put("domain", "pet_creation");
        metadata.put("assetGroup", "宠物素材");
        metadata.put("promptVersion", promptBuilder.promptVersion());
        metadata.put("styleVersion", promptBuilder.styleVersion());
        metadata.put("generationMode", draftValidator.resolveGenerationMode(draft));
        metadata.put("estimatedCreditCost", creditCost);
        metadata.put("referenceImageCount", imageUrls == null ? 0 : imageUrls.size());
        metadata.put("materialCount", array(draft, "materials").size());
        metadata.put("shotCount", array(draft, "shots").size());
        metadata.set("draftSnapshot", draft.deepCopy());
        metadata.set("materialSummary", materialSummary(draft));
        metadata.set("shotSummary", shotSummary(draft));
        metadata.set("stickerOverlay", stickerOverlaySummary(draft));
        metadata.set("warnings", objectMapper.valueToTree(draftValidator.warnings(draft)));
        return metadata;
    }

    private ObjectNode buildProviderPayloadPreview(ObjectNode draft, List<String> imageUrls, List<String> audioUrls, long creditCost) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("provider", "seedance");
        payload.put("businessType", "pet_creation");
        payload.put("businessDomain", "pet");
        payload.put("taskType", imageUrls == null || imageUrls.isEmpty()
                ? TaskTypeCode.SEEDANCE_TEXT_VIDEO
                : TaskTypeCode.SEEDANCE_REFERENCE_VIDEO);
        payload.put("generationMode", draftValidator.resolveGenerationMode(draft));
        payload.put("modelCode", modelCodeFor(imageUrls));
        payload.put("durationSeconds", duration(draft));
        payload.put("aspectRatio", aspectRatio(draft));
        payload.put("style", text(draft, "style", "cute"));
        payload.put("language", text(draft, "language", "zh-CN"));
        payload.put("watermark", false);
        payload.put("generateAudio", booleanValue(draft, "voiceEnabled"));
        payload.put("estimatedCreditCost", creditCost);
        payload.put("providerSubmitted", false);
        payload.put("taskCreated", false);
        payload.put("safetyIdentifier", "pet-creation-dry-run");
        payload.put("prompt", promptBuilder.build(draft));
        payload.put("negativePrompt", promptBuilder.negativePrompt());
        payload.set("imageUrls", objectMapper.valueToTree(imageUrls == null ? List.of() : imageUrls));
        payload.set("audioUrls", objectMapper.valueToTree(audioUrls == null ? List.of() : audioUrls));
        payload.set("characters", structuredField(draft, "characters", true));
        payload.set("storyboard", structuredField(draft, "shots", true));
        payload.set("dialogues", structuredField(draft, "dialogueLines", true));
        payload.set("subtitles", structuredField(draft, "subtitleConfig", false));
        payload.set("audioConfig", structuredField(draft, "audioConfig", false));
        payload.put("voiceProfileId", voiceProfileId(draft));
        payload.set("stickerOverlay", stickerOverlaySummary(draft));
        payload.set("diagnosticMetadata", buildTaskDiagnosticMetadata(draft, imageUrls, creditCost));
        return payload;
    }

    private JsonNode structuredField(JsonNode draft, String field, boolean arrayFallback) {
        JsonNode value = draft == null ? null : draft.get(field);
        if (value != null && !value.isNull()) {
            return value.deepCopy();
        }
        return arrayFallback ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
    }

    private String voiceProfileId(JsonNode draft) {
        for (String field : List.of("characters", "humanAssets", "dialogueLines")) {
            for (JsonNode item : array(draft, field)) {
                String value = text(item, "voiceProfileId");
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        JsonNode profiles = draft == null ? null : draft.path("audioConfig").path("voiceProfiles");
        if (profiles != null && profiles.isObject()) {
            var fields = profiles.fields();
            while (fields.hasNext()) {
                String value = text(fields.next().getValue(), "voiceProfileId");
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    private ObjectNode stickerOverlaySummary(JsonNode draft) {
        ObjectNode summary = objectMapper.createObjectNode();
        JsonNode visual = draft == null ? null : draft.get("visualSettings");
        JsonNode overlay = visual == null ? null : visual.get("stickerOverlay");
        JsonNode subtitleStyle = draft == null ? null : draft.get("subtitleStyle");
        summary.put("text", text(overlay, "text"));
        summary.put("fontFamily", text(subtitleStyle, "fontFamily"));
        summary.put("fontSize", subtitleStyle != null && subtitleStyle.has("fontSize") ? subtitleStyle.get("fontSize").asInt(0) : 0);
        summary.put("textColor", text(subtitleStyle, "textColor"));
        summary.put("outlineColor", text(subtitleStyle, "outlineColor"));
        summary.put("strokeStyle", text(subtitleStyle, "strokeMode"));
        summary.put("textX", overlay != null && overlay.has("textX") ? overlay.get("textX").asInt(0) : 0);
        summary.put("textY", overlay != null && overlay.has("textY") ? overlay.get("textY").asInt(0) : 0);
        summary.put("icon", text(overlay, "icon"));
        summary.put("iconX", overlay != null && overlay.has("iconX") ? overlay.get("iconX").asInt(0) : 0);
        summary.put("iconY", overlay != null && overlay.has("iconY") ? overlay.get("iconY").asInt(0) : 0);
        summary.put("staticFormat", text(overlay, "staticFormat"));
        summary.put("dynamicFormat", text(overlay, "dynamicFormat"));
        summary.put("outputType", "sticker".equals(text(draft, "videoType"))
                ? text(overlay, "dynamicFormat", text(overlay, "staticFormat", "gif"))
                : "");
        return summary;
    }

    private String modelCodeFor(List<String> imageUrls) {
        return imageUrls == null || imageUrls.isEmpty() ? seedanceModelCode : seedanceReferenceModelCode;
    }

    private ArrayNode materialSummary(JsonNode draft) {
        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode material : array(draft, "materials")) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", text(material, "role"));
            item.put("assetId", text(material, "assetId"));
            String url = text(material, "url");
            item.put("url", url);
            item.put("hasUrl", StringUtils.hasText(url));
            item.put("accessibility", StringUtils.hasText(url) ? "syntactic_check_only" : "missing_url");
            item.put("label", text(material, "label"));
            summary.add(item);
        }
        return summary;
    }

    private ArrayNode shotSummary(JsonNode draft) {
        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode shot : array(draft, "shots")) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("index", shot.has("index") ? shot.get("index").asInt() : 0);
            item.put("durationSeconds", shot.has("durationSeconds") ? shot.get("durationSeconds").asInt() : 0);
            item.put("hasFrameDescription", StringUtils.hasText(text(shot, "frameDescription")));
            item.put("hasAction", StringUtils.hasText(text(shot, "characterAction")));
            item.put("hasSubtitle", StringUtils.hasText(text(shot, "subtitle")));
            summary.add(item);
        }
        return summary;
    }

    private ArrayNode buildAdaptiveStoryboardShots(JsonNode draft) {
        ArrayNode adaptive = objectMapper.createArrayNode();
        ArrayNode existingShots = array(draft, "shots");
        ArrayNode dialogueLines = array(draft, "dialogueLines");
        int dialogueCount = countTextItems(dialogueLines, "text");
        int existingShotCount = existingShots.size();
        int targetCount = Math.min(6, Math.max(3, dialogueCount > 0 ? dialogueCount : Math.max(existingShotCount, 3)));
        int totalDuration = duration(draft);
        int baseShotDuration = Math.max(1, totalDuration / targetCount);
        int remainingSeconds = Math.max(0, totalDuration - baseShotDuration * targetCount);
        String scene = firstNonBlank(
                text(draft.get("visualSettings"), "backgroundPrompt"),
                materialLabel(draft, "scene"),
                "干净温暖的室内客厅"
        );
        String mainName = roleName(draft, 0, "主宠");
        String secondName = roleName(draft, 1, "伙伴");
        String humanName = roleName(draft, 2, "");
        boolean hasHuman = hasMaterialRole(draft, "human_avatar") || StringUtils.hasText(humanName);
        for (int i = 0; i < targetCount; i++) {
            JsonNode sourceShot = existingShotCount > i ? existingShots.get(i) : null;
            JsonNode line = dialogueLineAt(dialogueLines, i);
            String lineText = text(line, "text");
            ObjectNode shot = objectMapper.createObjectNode();
            shot.put("id", firstNonBlank(text(sourceShot, "id"), "shot-" + (i + 1)));
            shot.put("index", i + 1);
            shot.put("durationSeconds", baseShotDuration + (i < remainingSeconds ? 1 : 0));
            shot.put("frameDescription", firstNonBlank(
                    text(sourceShot, "frameDescription"),
                    adaptiveFrameDescription(draft, i, targetCount, scene, mainName, secondName, hasHuman)
            ));
            shot.put("characterAction", firstNonBlank(
                    text(sourceShot, "characterAction"),
                    adaptiveCharacterAction(i, targetCount, mainName, secondName, hasHuman, lineText)
            ));
            shot.put("cameraMove", firstNonBlank(
                    text(sourceShot, "cameraMove"),
                    i == 0 ? "稳定中景，轻微推进" : i == targetCount - 1 ? "固定近景收束" : "轻微跟拍，保持主体清晰"
            ));
            shot.put("subtitle", firstNonBlank(lineText, text(sourceShot, "subtitle"), shotSubtitle(draft, i)));
            shot.put("voiceEmotion", firstNonBlank(text(line, "emotion"), text(sourceShot, "voiceEmotion"), i == targetCount - 1 ? "惊讶" : "认真解释"));
            adaptive.add(shot);
        }
        return adaptive;
    }

    private int countTextItems(ArrayNode items, String fieldName) {
        int count = 0;
        for (JsonNode item : items) {
            if (StringUtils.hasText(text(item, fieldName))) {
                count++;
            }
        }
        return count;
    }

    private JsonNode dialogueLineAt(ArrayNode lines, int index) {
        int seen = 0;
        for (JsonNode line : lines) {
            if (!StringUtils.hasText(text(line, "text"))) {
                continue;
            }
            if (seen == index) {
                return line;
            }
            seen++;
        }
        return objectMapper.createObjectNode();
    }

    private String adaptiveFrameDescription(JsonNode draft,
                                            int index,
                                            int targetCount,
                                            String scene,
                                            String mainName,
                                            String secondName,
                                            boolean hasHuman) {
        if (index == 0) {
            return scene + "中建立人物和宠物关系，" + mainName + "作为主宠清晰出镜，保持参考图外观一致";
        }
        if (index == targetCount - 1) {
            return "收束到" + mainName + "的可爱反应，保留温暖结尾，画面干净且不遮挡字幕";
        }
        if (hasHuman) {
            return "主人与" + mainName + "自然互动，宠物先观察再回应，人物只作为陪伴角色";
        }
        return mainName + "与" + secondName + "围绕当前台词互动，两只宠物保持清晰分离，不融合、不换品种";
    }

    private String adaptiveCharacterAction(int index,
                                           int targetCount,
                                           String mainName,
                                           String secondName,
                                           boolean hasHuman,
                                           String lineText) {
        if (index == 0) {
            return mainName + "看向镜头或主人，做轻微歪头、眨眼等自然动作";
        }
        if (index == targetCount - 1) {
            return mainName + "用小幅表情完成反转或卖萌收尾";
        }
        if (hasHuman) {
            return "主人轻声互动，" + mainName + "靠近回应，动作简单自然";
        }
        if (StringUtils.hasText(lineText)) {
            return secondName + "提出反应，" + mainName + "根据台词做轻微表情回应";
        }
        return "按照剧情节奏完成自然表情和动作衔接";
    }

    private boolean hasMaterialRole(JsonNode draft, String role) {
        for (JsonNode material : array(draft, "materials")) {
            if (role.equals(text(material, "role"))) {
                return true;
            }
        }
        return false;
    }

    private String materialLabel(JsonNode draft, String role) {
        for (JsonNode material : array(draft, "materials")) {
            if (!role.equals(text(material, "role"))) {
                continue;
            }
            String label = firstNonBlank(text(material, "label"), text(material, "assetId"), text(material, "url"));
            if (StringUtils.hasText(label)) {
                return label;
            }
        }
        return "";
    }

    private ObjectNode normalizedDraft(JsonNode draft) {
        return draftValidator.normalize(draft);
    }

    private String requireTextPrompt(JsonNode draft) {
        String prompt = text(draft, "prompt");
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40000, "宠物视频生成需要填写创作需求");
        }
        return prompt;
    }

    private void applyGeneratedScript(ObjectNode draft, String prompt, boolean forceRegenerate) {
        boolean needsScript = !StringUtils.hasText(text(draft, "scriptText"));
        boolean hasExistingDialogue = hasDialogueText(draft);
        boolean needsDialogue = !hasExistingDialogue;
        boolean shouldTryAi = forceRegenerate || needsDialogue || (!hasExistingDialogue && needsScript);
        if (shouldTryAi && tryApplyArkGeneratedScript(draft)) {
            draft.put("videoType", "dialogue");
            draft.put("generationMode", PetCreationDraftValidator.MODE_DIALOGUE_VIDEO);
            return;
        }
        if (forceRegenerate || needsScript) {
            draft.put("scriptText", buildScriptText(prompt, draft));
        }
        if (needsDialogue || (forceRegenerate && !hasExistingDialogue)) {
            draft.set("dialogueLines", buildDialogueLines(draft, prompt));
        }
        stampScriptGenerationDiagnostics(draft, "local_template_fallback", null);
        draft.put("videoType", "dialogue");
        draft.put("generationMode", PetCreationDraftValidator.MODE_DIALOGUE_VIDEO);
    }

    private boolean tryApplyArkGeneratedScript(ObjectNode draft) {
        if (arkTextClient == null || !arkTextClient.available()) {
            return false;
        }
        try {
            ArkChatResult result = arkTextClient.chat(buildScriptGenerationPrompt(draft), petScriptModel, PET_SCRIPT_AI_TIMEOUT);
            ObjectNode generated = parseGeneratedScriptPayload(result.content());
            String scriptText = firstNonBlank(
                    text(generated, "scriptText"),
                    text(generated, "script"),
                    text(generated, "copy")
            );
            ArrayNode dialogueLines = normalizeGeneratedDialogueLines(generated.get("dialogueLines"), draft);
            if (!StringUtils.hasText(scriptText) && dialogueLines.isEmpty()) {
                return false;
            }
            if (StringUtils.hasText(scriptText)) {
                draft.put("scriptText", limit(scriptText, 600));
            }
            if (!dialogueLines.isEmpty()) {
                draft.set("dialogueLines", dialogueLines);
            }
            mergeGeneratedVisualSettings(draft, generated.get("visualSettings"));
            mergeGeneratedSubtitleStyle(draft, generated.get("subtitleStyle"));
            stampScriptGenerationDiagnostics(draft, "volcengine_ark", result.model());
            return true;
        } catch (Exception exception) {
            log.warn("Pet script Ark generation failed, fallback to local template: {}", exception.getMessage());
            return false;
        }
    }

    private void stampScriptGenerationDiagnostics(ObjectNode draft, String source, String model) {
        ObjectNode diagnostics = draft.has("diagnostics") && draft.get("diagnostics").isObject()
                ? (ObjectNode) draft.get("diagnostics")
                : objectMapper.createObjectNode();
        diagnostics.put("scriptModelSource", source);
        if (StringUtils.hasText(model)) {
            diagnostics.put("scriptModel", model);
        }
        diagnostics.put("scriptAiTimeoutSeconds", PET_SCRIPT_AI_TIMEOUT.toSeconds());
        diagnostics.put("scriptContextMaterialCount", array(draft, "materials").size());
        diagnostics.put("scriptContextDialogueCount", countTextItems(array(draft, "dialogueLines"), "text"));
        diagnostics.put("scriptContextShotCount", array(draft, "shots").size());
        draft.set("diagnostics", diagnostics);
    }

    private String buildScriptGenerationPrompt(JsonNode draft) {
        StringBuilder prompt = new StringBuilder(2600);
        prompt.append("你是一名萌宠短视频编剧和导演，需要根据用户在 AI 萌宠创作页输入的提示词，生成可编辑的双宠物对话草稿。\n");
        prompt.append("只输出严格 JSON，不要 Markdown，不要解释。JSON 格式：\n");
        prompt.append("{\"scriptText\":\"完整中文剧情/口播草稿\",\"dialogueLines\":[{\"speakerRoleId\":\"角色 id\",\"text\":\"单句台词，不超过 40 个汉字\",\"emotion\":\"委屈|开心|吐槽|认真解释|撒娇|惊讶\",\"speed\":\"slow|normal|fast\",\"voiceName\":\"中文音色名\",\"lipSync\":true}],\"visualSettings\":{\"cameraRhythm\":\"slow|balanced|fast|short_drama\",\"expressionIntensity\":70,\"stylePrompt\":\"可编辑画面风格描述，不超过160字\"},\"subtitleStyle\":{\"position\":\"bottom|middle|top\",\"fontSize\":34,\"strokeMode\":\"none|thin|strong\"}}\n");
        prompt.append("要求：\n");
        prompt.append("1. 所有展示给用户的文案必须是中文，适合短视频宠物拟人化对话，不能出现汽车销售、车型、试驾、价格、优惠等车辆内容。\n");
        prompt.append("2. dialogueLines 必须使用下面角色列表中的 speakerRoleId，双宠物对话至少 4 句，两个角色交替说话，单句台词不超过 40 个汉字。\n");
        prompt.append("3. 情绪和语速只能从 JSON 示例中的枚举取值；voiceName 用自然中文，例如“软萌童声”“机智少年音”。\n");
        prompt.append("4. scriptText 要概括完整剧情：前三秒钩子、中段冲突或解释、结尾可爱反转；不要原样复述系统要求。\n");
        prompt.append("5. 参数只建议影响宠物对话页面的可编辑草稿，不要要求直接创建视频任务。\n\n");
        prompt.append("用户提示词：").append(limit(text(draft, "prompt"), 500)).append("\n");
        prompt.append("视频参数：durationSeconds=").append(duration(draft))
                .append(", aspectRatio=").append(aspectRatio(draft))
                .append(", style=").append(text(draft, "style", "cute"))
                .append(", stylePrompt=").append(text(draft.get("visualSettings"), "stylePrompt"))
                .append(", subtitleEnabled=").append(booleanValue(draft, "subtitleEnabled"))
                .append(", voiceEnabled=").append(booleanValue(draft, "voiceEnabled"))
                .append(", lipSyncEnabled=").append(booleanValue(draft, "lipSyncEnabled"))
                .append("\n");
        prompt.append("角色列表：").append(roleSummaryForScriptPrompt(draft)).append("\n");
        prompt.append("素材清单：").append(materialSummaryForScriptPrompt(draft)).append("\n");
        prompt.append("已有台词：").append(dialogueSummaryForScriptPrompt(draft)).append("\n");
        prompt.append("已有分镜：").append(storyboardSummaryForScriptPrompt(draft)).append("\n");
        prompt.append("适配规则：如果已有台词不为空，必须保留这些台词表达的剧情和角色关系，后续分镜要围绕这些台词组织；如果已有分镜不为空，生成或改写台词时必须贴合这些镜头画面、动作和字幕意图。素材清单里的 main_pet、second_pet、human_avatar、scene、prop 都要作为当前用户实时选择的约束，不要凭空替换主体。\n");
        String backgroundPrompt = text(draft.get("visualSettings"), "backgroundPrompt");
        String stylePrompt = text(draft.get("visualSettings"), "stylePrompt");
        if (StringUtils.hasText(stylePrompt)) {
            prompt.append("风格描述：").append(limit(stylePrompt, 160)).append("\n");
        }
        if (StringUtils.hasText(backgroundPrompt)) {
            prompt.append("背景/场景要求：").append(limit(backgroundPrompt, 160)).append("\n");
        }
        return prompt.toString();
    }

    private String roleSummaryForScriptPrompt(JsonNode draft) {
        ArrayNode roles = array(draft, "roles");
        if (roles.isEmpty()) {
            return "[{\"id\":\"role-main\",\"name\":\"主宠\",\"type\":\"cat\"},{\"id\":\"role-second\",\"name\":\"伙伴\",\"type\":\"dog\"}]";
        }
        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode role : roles) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", text(role, "id"));
            item.put("name", text(role, "name", "宠物"));
            item.put("type", text(role, "type", "other"));
            item.put("speakingTone", text(role, "speakingTone"));
            item.set("personalityTags", array(role, "personalityTags"));
            summary.add(item);
        }
        return summary.toString();
    }

    private String materialSummaryForScriptPrompt(JsonNode draft) {
        ArrayNode materials = array(draft, "materials");
        if (materials.isEmpty()) {
            return "[]";
        }
        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode material : materials) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", text(material, "role"));
            item.put("assetId", text(material, "assetId"));
            item.put("label", text(material, "label"));
            item.put("hasUrl", StringUtils.hasText(text(material, "url")));
            item.put("url", limit(text(material, "url"), 180));
            summary.add(item);
        }
        return summary.toString();
    }

    private String dialogueSummaryForScriptPrompt(JsonNode draft) {
        ArrayNode lines = array(draft, "dialogueLines");
        if (lines.isEmpty()) {
            return "[]";
        }
        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode line : lines) {
            String lineText = text(line, "text");
            if (!StringUtils.hasText(lineText)) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("speakerRoleId", text(line, "speakerRoleId"));
            item.put("text", limit(lineText, 80));
            item.put("emotion", text(line, "emotion"));
            item.put("speed", text(line, "speed"));
            item.put("voiceName", text(line, "voiceName"));
            summary.add(item);
        }
        return summary.toString();
    }

    private String storyboardSummaryForScriptPrompt(JsonNode draft) {
        ArrayNode shots = array(draft, "shots");
        if (shots.isEmpty()) {
            return "[]";
        }
        ArrayNode summary = objectMapper.createArrayNode();
        int max = Math.min(6, shots.size());
        for (int i = 0; i < max; i++) {
            JsonNode shot = shots.get(i);
            ObjectNode item = objectMapper.createObjectNode();
            item.put("index", shot.has("index") ? shot.get("index").asInt(i + 1) : i + 1);
            item.put("durationSeconds", shot.has("durationSeconds") ? shot.get("durationSeconds").asInt(0) : 0);
            item.put("frameDescription", limit(text(shot, "frameDescription"), 120));
            item.put("characterAction", limit(text(shot, "characterAction"), 100));
            item.put("cameraMove", limit(text(shot, "cameraMove"), 60));
            item.put("subtitle", limit(text(shot, "subtitle"), 80));
            summary.add(item);
        }
        return summary.toString();
    }

    private ObjectNode parseGeneratedScriptPayload(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJsonPayload(content));
        if (!root.isObject()) {
            throw new BusinessException(50214, "Pet script response is not an object");
        }
        return (ObjectNode) root;
    }

    private String extractJsonPayload(String content) {
        String value = content == null ? "" : content.trim();
        value = value.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        int start = value.indexOf('{');
        if (start > 0) {
            value = value.substring(start);
        }
        int end = value.lastIndexOf('}');
        if (end >= 0 && end + 1 < value.length()) {
            value = value.substring(0, end + 1);
        }
        return value;
    }

    private ArrayNode normalizeGeneratedDialogueLines(JsonNode source, JsonNode draft) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return normalized;
        }
        int max = Math.min(8, source.size());
        for (int i = 0; i < max; i++) {
            JsonNode item = source.get(i);
            String lineText = firstNonBlank(
                    text(item, "text"),
                    text(item, "line"),
                    text(item, "content")
            );
            if (!StringUtils.hasText(lineText)) {
                continue;
            }
            ObjectNode line = objectMapper.createObjectNode();
            line.put("id", "line-ai-" + (normalized.size() + 1));
            line.put("speakerRoleId", resolveSpeakerRoleId(item, draft, normalized.size()));
            line.put("text", limit(cleanDialogueText(lineText), 80));
            line.put("emotion", normalizeDialogueEmotion(text(item, "emotion"), normalized.size()));
            line.put("speed", normalizeDialogueSpeed(firstNonBlank(text(item, "speed"), text(item, "speechSpeed"))));
            line.put("voiceName", firstNonBlank(text(item, "voiceName"), defaultVoiceName(normalized.size())));
            line.put("lipSync", item != null && item.has("lipSync") ? item.get("lipSync").asBoolean(booleanValue(draft, "lipSyncEnabled")) : booleanValue(draft, "lipSyncEnabled"));
            normalized.add(line);
        }
        return normalized;
    }

    private String resolveSpeakerRoleId(JsonNode item, JsonNode draft, int index) {
        ArrayNode roles = array(draft, "roles");
        String explicit = firstNonBlank(
                text(item, "speakerRoleId"),
                text(item, "roleId"),
                text(item, "speakerId")
        );
        if (StringUtils.hasText(explicit)) {
            for (JsonNode role : roles) {
                if (explicit.equals(text(role, "id"))) {
                    return explicit;
                }
            }
        }
        String speakerName = firstNonBlank(text(item, "speaker"), text(item, "roleName"), text(item, "name"));
        if (StringUtils.hasText(speakerName)) {
            for (JsonNode role : roles) {
                if (speakerName.equals(text(role, "name"))) {
                    return text(role, "id");
                }
            }
        }
        int roleIndex = index;
        if (item != null) {
            roleIndex = item.has("roleIndex") ? item.get("roleIndex").asInt(index) : item.has("speakerIndex") ? item.get("speakerIndex").asInt(index) : index;
            if (roleIndex > 0 && roleIndex <= roles.size()) {
                roleIndex = roleIndex - 1;
            }
        }
        if (!roles.isEmpty()) {
            return text(roles.get(Math.floorMod(roleIndex, roles.size())), "id", "role-main");
        }
        return index % 2 == 0 ? "role-main" : "role-second";
    }

    private void mergeGeneratedVisualSettings(ObjectNode draft, JsonNode visualSettings) {
        if (visualSettings == null || !visualSettings.isObject()) {
            return;
        }
        ObjectNode target = draft.has("visualSettings") && draft.get("visualSettings").isObject()
                ? (ObjectNode) draft.get("visualSettings")
                : objectMapper.createObjectNode();
        String rhythm = text(visualSettings, "cameraRhythm");
        if (Set.of("slow", "balanced", "fast", "short_drama").contains(rhythm)) {
            target.put("cameraRhythm", rhythm);
        }
        if (visualSettings.has("expressionIntensity") && visualSettings.get("expressionIntensity").isNumber()) {
            target.put("expressionIntensity", Math.max(0, Math.min(100, visualSettings.get("expressionIntensity").asInt())));
        }
        String stylePrompt = text(visualSettings, "stylePrompt");
        if (StringUtils.hasText(stylePrompt)) {
            target.put("stylePrompt", limit(stylePrompt, 160));
        }
        draft.set("visualSettings", target);
    }

    private void mergeGeneratedSubtitleStyle(ObjectNode draft, JsonNode subtitleStyle) {
        if (subtitleStyle == null || !subtitleStyle.isObject()) {
            return;
        }
        ObjectNode target = draft.has("subtitleStyle") && draft.get("subtitleStyle").isObject()
                ? (ObjectNode) draft.get("subtitleStyle")
                : objectMapper.createObjectNode();
        String position = text(subtitleStyle, "position");
        if (Set.of("bottom", "middle", "top").contains(position)) {
            target.put("position", position);
        }
        if (subtitleStyle.has("fontSize") && subtitleStyle.get("fontSize").isNumber()) {
            target.put("fontSize", Math.max(18, Math.min(56, subtitleStyle.get("fontSize").asInt())));
        }
        String strokeMode = text(subtitleStyle, "strokeMode");
        if (Set.of("none", "thin", "strong").contains(strokeMode)) {
            target.put("strokeMode", strokeMode);
        }
        draft.set("subtitleStyle", target);
    }

    private boolean hasDialogueText(JsonNode draft) {
        for (JsonNode line : array(draft, "dialogueLines")) {
            if (StringUtils.hasText(text(line, "text"))) {
                return true;
            }
        }
        return false;
    }

    private String buildScriptText(String prompt, JsonNode draft) {
        String mainRole = roleName(draft, 0, "主宠");
        String secondRole = roleName(draft, 1, "伙伴");
        String existingDialogue = dialogueScriptText(draft);
        if (StringUtils.hasText(existingDialogue)) {
            return existingDialogue;
        }
        String existingStoryboard = storyboardScriptText(draft);
        if (StringUtils.hasText(existingStoryboard)) {
            return existingStoryboard;
        }
        return mainRole + "看向镜头，围绕“" + limit(prompt, 60) + "”做出反应。\n"
                + secondRole + "加入互动，形成一句轻松的反差或回应。\n"
                + "结尾保留一个适合短视频传播的萌点、笑点或情绪反转。";
    }

    private String dialogueScriptText(JsonNode draft) {
        ArrayNode lines = array(draft, "dialogueLines");
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("根据当前已填写台词组织剧情：\n");
        for (JsonNode line : lines) {
            String lineText = text(line, "text");
            if (!StringUtils.hasText(lineText)) {
                continue;
            }
            builder.append(roleNameById(draft, text(line, "speakerRoleId")))
                    .append("：")
                    .append(limit(lineText, 80))
                    .append("\n");
        }
        return builder.length() > 14 ? limit(builder.toString(), 600) : "";
    }

    private String storyboardScriptText(JsonNode draft) {
        ArrayNode shots = array(draft, "shots");
        if (shots.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("根据当前已填写分镜组织台词和剧情：\n");
        for (JsonNode shot : shots) {
            String frame = text(shot, "frameDescription");
            String action = text(shot, "characterAction");
            if (!StringUtils.hasText(frame) && !StringUtils.hasText(action)) {
                continue;
            }
            builder.append("镜头")
                    .append(shot.has("index") ? shot.get("index").asInt() : builder.length())
                    .append("：")
                    .append(limit(frame + " " + action, 100))
                    .append("\n");
        }
        return builder.length() > 16 ? limit(builder.toString(), 600) : "";
    }

    private String roleNameById(JsonNode draft, String roleId) {
        if (StringUtils.hasText(roleId)) {
            for (JsonNode role : array(draft, "roles")) {
                if (roleId.equals(text(role, "id"))) {
                    return text(role, "name", "宠物");
                }
            }
        }
        return "宠物";
    }

    private ArrayNode buildDialogueLines(JsonNode draft, String prompt) {
        ArrayNode lines = objectMapper.createArrayNode();
        String topic = dialogueTopic(prompt);
        String[] texts = {
                "我先解释一下，" + topic + "不是你想的那样。",
                "那你把原因讲清楚，我可都看见了。",
                "我只是想确认一下，没想到被发现了。",
                "好吧，下次记得带上我一起。"
        };
        String[] emotions = {"认真解释", "吐槽", "撒娇", "开心"};
        String[] voices = {"软萌童声", "机智少年音", "软萌童声", "机智少年音"};
        for (int i = 0; i < texts.length; i++) {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("id", "line-" + (i + 1));
            line.put("speakerRoleId", text(roleAt(draft, i % 2), "id", i % 2 == 0 ? "role-main" : "role-second"));
            line.put("text", limit(texts[i], 80));
            line.put("emotion", emotions[i]);
            line.put("speed", "normal");
            line.put("voiceName", voices[i]);
            line.put("lipSync", booleanValue(draft, "lipSyncEnabled"));
            lines.add(line);
        }
        return lines;
    }

    private String dialogueTopic(String prompt) {
        String normalized = limit(prompt, 80);
        if (normalized.contains("，")) {
            normalized = normalized.substring(0, normalized.indexOf("，"));
        } else if (normalized.contains(",")) {
            normalized = normalized.substring(0, normalized.indexOf(","));
        } else if (normalized.contains("。")) {
            normalized = normalized.substring(0, normalized.indexOf("。"));
        }
        normalized = normalized.replaceFirst("^小[猫狗](把|在)?", "").trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = "这件事";
        }
        return normalized.length() > 14 ? normalized.substring(0, 14) + "..." : normalized;
    }

    private String buildSeedancePrompt(JsonNode draft) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a polished short pet video. ");
        prompt.append("Core idea: ").append(requireTextPrompt(draft)).append(". ");
        prompt.append("Style: ").append(text(draft, "style", "cute")).append(". ");
        prompt.append("Aspect ratio: ").append(aspectRatio(draft)).append(". ");
        prompt.append("Keep the pet's appearance, fur pattern, face, and body shape consistent across shots. ");
        appendIfText(prompt, "Editable style direction", text(draft.get("visualSettings"), "stylePrompt"));
        appendIfText(prompt, "Background and scene edit", text(draft.get("visualSettings"), "backgroundPrompt"));
        appendIfText(prompt, "Script", text(draft, "scriptText"));
        appendDialogue(prompt, draft);
        appendShots(prompt, draft);
        prompt.append("Use clear composition, natural pet movement, soft lighting, and avoid distorted limbs or extra animals. ");
        if (booleanValue(draft, "subtitleEnabled")) {
            prompt.append("Leave safe space for Chinese subtitles near the bottom. ");
        }
        return limit(prompt.toString(), 1800);
    }

    private void appendIfText(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(label).append(": ").append(value.trim()).append(". ");
        }
    }

    private void appendDialogue(StringBuilder builder, JsonNode draft) {
        ArrayNode lines = array(draft, "dialogueLines");
        if (lines.isEmpty()) {
            return;
        }
        builder.append("Dialogue beats: ");
        for (JsonNode line : lines) {
            String text = text(line, "text");
            if (StringUtils.hasText(text)) {
                builder.append(text).append(" ");
            }
        }
    }

    private void appendShots(StringBuilder builder, JsonNode draft) {
        ArrayNode shots = array(draft, "shots");
        if (shots.isEmpty()) {
            return;
        }
        builder.append("Storyboard: ");
        for (JsonNode shot : shots) {
            String frame = text(shot, "frameDescription");
            String action = text(shot, "characterAction");
            if (StringUtils.hasText(frame) || StringUtils.hasText(action)) {
                builder.append("Shot ").append(shot.get("index") == null ? "" : shot.get("index").asText())
                        .append(": ").append(frame).append(" ").append(action).append(". ");
            }
        }
    }

    private List<String> referenceImageUrls(JsonNode draft) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addMaterialUrls(draft, urls, "main_pet", 3);
        if ("dialogue".equals(text(draft, "videoType")) || PetCreationDraftValidator.MODE_DIALOGUE_VIDEO.equals(text(draft, "generationMode"))) {
            addMaterialUrls(draft, urls, "second_pet", 3);
        }
        addMaterialUrls(draft, urls, "human_avatar", 2);
        addMaterialUrls(draft, urls, "scene", 2);
        addMaterialUrls(draft, urls, "prop", 2);
        return new ArrayList<>(urls);
    }

    private void addMaterialUrls(JsonNode draft, LinkedHashSet<String> urls, String role, int limit) {
        int added = 0;
        for (JsonNode material : array(draft, "materials")) {
            if (urls.size() >= 6 || added >= limit || !role.equals(text(material, "role"))) {
                continue;
            }
            String url = text(material, "url");
            if (StringUtils.hasText(url)) {
                urls.add(url);
                added++;
            }
        }
    }

    private List<String> referenceAudioUrls(JsonNode draft) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (JsonNode material : array(draft, "materials")) {
            String url = text(material, "url");
            if ("audio".equals(text(material, "role")) && StringUtils.hasText(url)) {
                urls.add(url);
            }
        }
        return new ArrayList<>(urls);
    }

    private PetVideoWorkEntity requireWork(Long workId, Long ownerUserId) {
        if (workId == null) {
            throw new BusinessException(40000, "workId is required");
        }
        LambdaQueryWrapper<PetVideoWorkEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PetVideoWorkEntity::getWorkId, workId)
                .eq(PetVideoWorkEntity::getOwnerUserId, ownerUserId)
                .eq(PetVideoWorkEntity::getDeleted, 0)
                .last("limit 1");
        PetVideoWorkEntity work = workMapper.selectOne(wrapper);
        if (work == null) {
            throw new BusinessException(40400, "Pet work not found");
        }
        return work;
    }

    private PetVideoWorkEntity findByTaskId(Long taskId, Long ownerUserId) {
        if (taskId == null) {
            return null;
        }
        LambdaQueryWrapper<PetVideoWorkEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PetVideoWorkEntity::getTaskId, taskId)
                .eq(PetVideoWorkEntity::getOwnerUserId, ownerUserId)
                .eq(PetVideoWorkEntity::getDeleted, 0)
                .last("limit 1");
        return workMapper.selectOne(wrapper);
    }

    private PetVideoTaskResponse toTaskResponse(TaskItem task, PetVideoWorkEntity work) {
        String status = taskStatus(task == null ? null : task.status());
        if (task != null) {
            syncWorkFromTask(work, task, status);
        }
        JsonNode draft = readDraft(work);
        String previewUrl = work.getVideoUrl();
        if (!StringUtils.hasText(previewUrl)) {
            previewUrl = firstText(task == null ? null : task.outputJson(), "videoUrl");
        }
        return new PetVideoTaskResponse(
                String.valueOf(task.taskId()),
                work.getTitle(),
                status,
                progress(task, status),
                statusLabel(status),
                "completed".equals(status) || "failed".equals(status) ? 0 : 60,
                draft,
                previewUrl,
                String.valueOf(work.getWorkId()),
                task == null ? null : petErrorCode(task),
                task == null ? null : isRetryable(task),
                task == null ? null : task.errorMessage(),
                formatTime(task == null ? null : task.createdAt())
        );
    }

    private PetWorkResponse toWorkResponse(PetVideoWorkEntity work, Long ownerUserId) {
        JsonNode draft = readDraft(work);
        String status = workStatus(work.getStatus());
        String videoUrl = work.getVideoUrl();
        String coverUrl = work.getCoverUrl();
        LocalDateTime createdAt = work.getCreatedAt();
        String errorCode = null;
        String errorMessage = null;
        Boolean retryable = null;
        if (work.getTaskId() != null) {
            TaskItem task = taskService.getTaskForViewer(work.getTaskId(), OptionalLong.of(ownerUserId));
            String currentTaskStatus = taskStatus(task.status());
            syncWorkFromTask(work, task, currentTaskStatus);
            errorCode = petErrorCode(task);
            errorMessage = task.errorMessage();
            retryable = isRetryable(task);
            status = switch (currentTaskStatus) {
                case "queued" -> "running";
                case "canceled" -> "failed";
                default -> currentTaskStatus;
            };
            videoUrl = work.getVideoUrl();
            coverUrl = work.getCoverUrl();
            String outputVideo = firstText(task.outputJson(), "videoUrl");
            String outputCover = firstText(task.outputJson(), "firstFrameUrl");
            if (!StringUtils.hasText(videoUrl) && StringUtils.hasText(outputVideo)) {
                videoUrl = outputVideo;
            }
            if (!StringUtils.hasText(coverUrl) && StringUtils.hasText(outputCover)) {
                coverUrl = outputCover;
            }
            if (StringUtils.hasText(task.errorMessage()) && "failed".equals(status)) {
                videoUrl = null;
            }
            createdAt = task.createdAt();
        }
        return new PetWorkResponse(
                String.valueOf(work.getWorkId()),
                work.getTitle(),
                templateTitle(draft),
                work.getPetType(),
                status,
                work.getAspectRatio(),
                work.getDurationSeconds(),
                coverUrl,
                videoUrl,
                videoUrl,
                draft,
                errorCode,
                errorMessage,
                retryable,
                formatTime(createdAt)
        );
    }

    private void syncWorkFromTask(PetVideoWorkEntity work, TaskItem task, String taskStatus) {
        if (work == null || task == null) {
            return;
        }
        boolean changed = false;
        String nextStatus = switch (taskStatus) {
            case "completed" -> STATUS_COMPLETED;
            case "failed", "canceled" -> STATUS_FAILED;
            case "queued", "running" -> STATUS_RUNNING;
            default -> work.getStatus();
        };
        if (StringUtils.hasText(nextStatus) && !nextStatus.equals(work.getStatus())) {
            work.setStatus(nextStatus);
            changed = true;
        }
        String outputVideo = firstText(task.outputJson(), "videoUrl");
        String outputCover = firstText(task.outputJson(), "firstFrameUrl");
        if (!StringUtils.hasText(outputCover)) {
            outputCover = firstText(task.outputJson(), "coverUrl");
        }
        if (StringUtils.hasText(outputVideo) && TaskStatusCode.SUCCESS.equals(task.status())) {
            outputVideo = resolveStickerGifOutput(work, task, outputVideo);
        }
        if (StringUtils.hasText(outputVideo) && !outputVideo.equals(work.getVideoUrl())) {
            work.setVideoUrl(outputVideo);
            changed = true;
        }
        if (StringUtils.hasText(outputCover) && !outputCover.equals(work.getCoverUrl())) {
            work.setCoverUrl(outputCover);
            changed = true;
        }
        if (task.resultAssetId() != null && !task.resultAssetId().equals(work.getResultAssetId())) {
            work.setResultAssetId(task.resultAssetId());
            changed = true;
        }
        if (TaskStatusCode.SUCCESS.equals(task.status()) && work.getCompletedAt() == null) {
            work.setCompletedAt(task.finishedAt() == null ? LocalDateTime.now() : task.finishedAt());
            changed = true;
        }
        if (TaskStatusCode.FAILED.equals(task.status()) || TaskStatusCode.CANCELED.equals(task.status())) {
            String petErrorCode = petErrorCode(task);
            if (!equalsText(petErrorCode, work.getErrorCode())) {
                work.setErrorCode(petErrorCode);
                changed = true;
            }
            if (!equalsText(task.errorMessage(), work.getErrorMessage())) {
                work.setErrorMessage(limit(task.errorMessage(), 1000));
                changed = true;
            }
            Integer retryable = Boolean.TRUE.equals(isRetryable(task)) ? 1 : 0;
            if (!retryable.equals(work.getRetryable())) {
                work.setRetryable(retryable);
                changed = true;
            }
        }
        if (StringUtils.hasText(task.outputJson()) && !equalsText(task.outputJson(), work.getProviderMetadataJson())) {
            work.setProviderMetadataJson(limit(task.outputJson(), 5000));
            changed = true;
        }
        if (changed) {
            work.setUpdatedAt(LocalDateTime.now());
            workMapper.updateById(work);
        }
    }

    private String resolveStickerGifOutput(PetVideoWorkEntity work, TaskItem task, String providerVideoUrl) {
        if (!requiresStickerGif(work) || !StringUtils.hasText(providerVideoUrl)) {
            return providerVideoUrl;
        }
        StickerOverlaySpec overlay = stickerOverlaySpec(work);
        String existingVideoUrl = work.getVideoUrl();
        if (isGifUrl(existingVideoUrl)
                && (!existingVideoUrl.equals(providerVideoUrl) || !overlay.hasOverlay())) {
            return existingVideoUrl;
        }
        if (isGifUrl(providerVideoUrl) && !overlay.hasOverlay()) {
            return providerVideoUrl;
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("pet-sticker-gif-");
            Path source = tempDir.resolve(isGifUrl(providerVideoUrl) ? "source.gif" : "source.mp4");
            Path gif = tempDir.resolve("sticker.gif");
            downloadToFile(providerVideoUrl, source);
            renderStickerGif(source, gif, overlay);
            long size = Files.size(gif);
            String fileName = (overlay.hasOverlay() ? STICKER_OVERLAY_FILE_MARKER : "pet-sticker-")
                    + task.taskId() + "-" + UUID.randomUUID() + ".gif";
            try (InputStream in = Files.newInputStream(gif)) {
                UploadResult uploaded = storageService.upload(in, size, fileName, "image/gif", "video");
                if (uploaded != null && StringUtils.hasText(uploaded.url())) {
                    log.info("Converted pet sticker task {} output to GIF: {}", task.taskId(), uploaded.url());
                    return uploaded.url();
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to convert pet sticker task {} output to GIF, keeping provider video: {}",
                    task.taskId(), ex.getMessage());
        } finally {
            deleteQuietly(tempDir);
        }
        return providerVideoUrl;
    }

    private boolean requiresStickerGif(PetVideoWorkEntity work) {
        JsonNode draft = readDraft(work);
        JsonNode overlay = draft.path("visualSettings").path("stickerOverlay");
        return "pet-sticker".equals(text(draft, "templateId"))
                && "gif".equalsIgnoreCase(text(overlay, "dynamicFormat"));
    }

    private boolean isGifUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return normalized.endsWith(".gif");
    }

    private void downloadToFile(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("download failed: HTTP " + response.statusCode());
        }
    }

    private void renderStickerGif(Path source, Path gif, StickerOverlaySpec overlay) throws Exception {
        String filter = stickerGifFilter(overlay);
        Process process = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", source.toString(),
                "-vf", filter,
                "-loop", "0",
                gif.toString()
        ).redirectErrorStream(true).start();
        boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg gif conversion timed out");
        }
        if (process.exitValue() != 0 || !Files.exists(gif) || Files.size(gif) <= 0) {
            throw new IllegalStateException("ffmpeg gif conversion failed with exit " + process.exitValue());
        }
    }

    private String stickerGifFilter(StickerOverlaySpec overlay) {
        StringBuilder filter = new StringBuilder("fps=12,scale=512:-1:flags=lanczos");
        if (overlay == null) {
            return filter.toString();
        }
        int strokeWidth = strokeWidth(overlay.strokeStyle());
        if (StringUtils.hasText(overlay.text())) {
            appendDrawText(filter, overlay.text(), overlay.textX(), overlay.textY(),
                    overlay.fontSize(), overlay.textColor(), overlay.outlineColor(), strokeWidth);
        }
        String iconText = iconText(overlay.icon());
        if (StringUtils.hasText(iconText)) {
            appendDrawText(filter, iconText, overlay.iconX(), overlay.iconY(),
                    Math.max(24, overlay.fontSize() + 8), overlay.textColor(), overlay.outlineColor(),
                    Math.max(1, strokeWidth));
        }
        return filter.toString();
    }

    private void appendDrawText(StringBuilder filter, String value, int xPercent, int yPercent,
                                int fontSize, String color, String borderColor, int borderWidth) {
        double x = Math.max(0, Math.min(100, xPercent)) / 100.0;
        double y = Math.max(0, Math.min(100, yPercent)) / 100.0;
        filter.append(",drawtext=");
        String fontFile = stickerFontFile();
        if (StringUtils.hasText(fontFile)) {
            filter.append("fontfile=").append(escapeDrawTextValue(fontFile)).append(":");
        }
        filter.append("text='").append(escapeDrawTextValue(value)).append("'")
                .append(":fontcolor=").append(normalizeFfmpegColor(color, "0xffffff"))
                .append(":fontsize=").append(Math.max(18, Math.min(72, fontSize)))
                .append(":x=(w-text_w)*").append(String.format(Locale.ROOT, "%.2f", x))
                .append(":y=(h-text_h)*").append(String.format(Locale.ROOT, "%.2f", y));
        if (borderWidth > 0) {
            filter.append(":borderw=").append(borderWidth)
                    .append(":bordercolor=").append(normalizeFfmpegColor(borderColor, "0x111111"));
        }
    }

    private StickerOverlaySpec stickerOverlaySpec(PetVideoWorkEntity work) {
        JsonNode draft = readDraft(work);
        JsonNode overlay = draft.path("visualSettings").path("stickerOverlay");
        JsonNode subtitleStyle = draft.path("subtitleStyle");
        return new StickerOverlaySpec(
                text(overlay, "text", text(draft, "scriptText")),
                overlay.path("textX").asInt(50),
                overlay.path("textY").asInt(82),
                text(overlay, "icon"),
                overlay.path("iconX").asInt(82),
                overlay.path("iconY").asInt(22),
                text(subtitleStyle, "fontFamily", "Arial Black"),
                subtitleStyle.path("fontSize").asInt(34),
                text(subtitleStyle, "textColor", "#2563eb"),
                text(subtitleStyle, "outlineColor", "#ffffff"),
                text(subtitleStyle, "strokeMode", "strong")
        );
    }

    private String stickerFontFile() {
        String[] candidates = {
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
        };
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return "";
    }

    private String escapeDrawTextValue(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'")
                .replace("%", "\\%");
    }

    private String normalizeFfmpegColor(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.matches("#[0-9a-fA-F]{6}")) {
            return "0x" + trimmed.substring(1);
        }
        if (trimmed.matches("0x[0-9a-fA-F]{6}")) {
            return trimmed;
        }
        return trimmed;
    }

    private int strokeWidth(String strokeStyle) {
        String value = strokeStyle == null ? "" : strokeStyle.trim().toLowerCase(Locale.ROOT);
        if ("strong".equals(value)) {
            return 4;
        }
        if ("light".equals(value) || "thin".equals(value)) {
            return 2;
        }
        return 0;
    }

    private String iconText(String icon) {
        String value = icon == null ? "" : icon.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "sparkle", "star" -> "✦";
            case "heart" -> "♥";
            case "paw" -> "●";
            default -> "";
        };
    }

    private record StickerOverlaySpec(String text,
                                      int textX,
                                      int textY,
                                      String icon,
                                      int iconX,
                                      int iconY,
                                      String fontFamily,
                                      int fontSize,
                                      String textColor,
                                      String outlineColor,
                                      String strokeStyle) {
        boolean hasOverlay() {
            return StringUtils.hasText(text) || StringUtils.hasText(icon);
        }
    }

    private void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private boolean equalsText(String left, String right) {
        return String.valueOf(left == null ? "" : left).equals(String.valueOf(right == null ? "" : right));
    }

    private Boolean isRetryable(TaskItem task) {
        if (task == null) {
            return null;
        }
        String errorCode = task.errorCode();
        if (!StringUtils.hasText(errorCode)) {
            return false;
        }
        return Set.of(
                "TASK_RETRYABLE",
                "PROVIDER_SUBMIT_FAILED",
                "PROVIDER_TIMEOUT",
                "PROVIDER_GENERATION_FAILED",
                "RESULT_SAVE_FAILED",
                "WORK_SYNC_FAILED"
        ).contains(errorCode);
    }

    private String petErrorCode(TaskItem task) {
        if (task == null) {
            return null;
        }
        if (!TaskStatusCode.FAILED.equals(task.status())
                && !TaskStatusCode.RETRYABLE.equals(task.status())
                && !TaskStatusCode.CANCELED.equals(task.status())) {
            return null;
        }
        String raw = task.errorCode();
        if (StringUtils.hasText(raw)
                && !"TASK_FAILED".equals(raw)
                && !"TASK_RETRYABLE".equals(raw)) {
            return raw;
        }
        String message = task.errorMessage() == null ? "" : task.errorMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("超时")) {
            return "PROVIDER_TIMEOUT";
        }
        if (message.contains("submit") || message.contains("create") || message.contains("提交") || message.contains("受理")) {
            return "PROVIDER_SUBMIT_FAILED";
        }
        if (message.contains("save") || message.contains("保存") || message.contains("asset")) {
            return "RESULT_SAVE_FAILED";
        }
        return Boolean.TRUE.equals(isRetryable(task)) ? "PROVIDER_TIMEOUT" : "PROVIDER_GENERATION_FAILED";
    }

    private String taskStatus(String rawStatus) {
        if (TaskStatusCode.QUEUED.equals(rawStatus)) {
            return "queued";
        }
        if (TaskStatusCode.RUNNING.equals(rawStatus)) {
            return "running";
        }
        if (TaskStatusCode.SUCCESS.equals(rawStatus)) {
            return "completed";
        }
        if (TaskStatusCode.CANCELED.equals(rawStatus)) {
            return "canceled";
        }
        return "failed";
    }

    private String workStatus(String rawStatus) {
        if (STATUS_DRAFT.equalsIgnoreCase(rawStatus)) {
            return "draft";
        }
        if (STATUS_RUNNING.equalsIgnoreCase(rawStatus)) {
            return "running";
        }
        if (STATUS_COMPLETED.equalsIgnoreCase(rawStatus)) {
            return "completed";
        }
        if (STATUS_FAILED.equalsIgnoreCase(rawStatus)) {
            return "failed";
        }
        return "draft";
    }

    private String normalizeFilter(String status) {
        if (!StringUtils.hasText(status) || "all".equalsIgnoreCase(status)) {
            return null;
        }
        String value = status.trim().toLowerCase(Locale.ROOT);
        if (Set.of("draft", "running", "completed", "failed").contains(value)) {
            return value;
        }
        return null;
    }

    private String normalizePetTypeFilter(String petType) {
        if (!StringUtils.hasText(petType) || "all".equalsIgnoreCase(petType)) {
            return null;
        }
        String value = petType.trim().toLowerCase(Locale.ROOT);
        return Set.of("cat", "dog", "other").contains(value) ? value : null;
    }

    private Integer progress(TaskItem task, String status) {
        if (task != null && task.progress() != null) {
            return Math.max(0, Math.min(100, task.progress()));
        }
        if ("completed".equals(status) || "failed".equals(status) || "canceled".equals(status)) {
            return 100;
        }
        return "queued".equals(status) ? 0 : 50;
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "queued" -> "等待生成";
            case "running" -> "生成中";
            case "completed" -> "生成完成";
            case "canceled" -> "已取消";
            default -> "生成失败";
        };
    }

    private JsonNode readDraft(PetVideoWorkEntity work) {
        try {
            return objectMapper.readTree(work.getDraftJson());
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String firstText(String json, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode value = node.findValue(fieldName);
            return value != null && value.isTextual() ? value.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ArrayNode array(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode roleAt(JsonNode draft, int index) {
        ArrayNode roles = array(draft, "roles");
        return roles.size() > index ? roles.get(index) : objectMapper.createObjectNode();
    }

    private String roleName(JsonNode draft, int index, String fallback) {
        return text(roleAt(draft, index), "name", fallback);
    }

    private String shotSubtitle(JsonNode draft, int index) {
        ArrayNode lines = array(draft, "dialogueLines");
        if (lines.size() > index) {
            String text = text(lines.get(index), "text");
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return index == 0 ? limit(requireTextPrompt(draft), 24) : "保持节奏，突出宠物反应";
    }

    private String normalizeDialogueEmotion(String value, int index) {
        if (DIALOGUE_EMOTIONS.contains(value)) {
            return value;
        }
        return switch (Math.floorMod(index, 4)) {
            case 1 -> "吐槽";
            case 2 -> "撒娇";
            case 3 -> "开心";
            default -> "认真解释";
        };
    }

    private String normalizeDialogueSpeed(String value) {
        if (DIALOGUE_SPEEDS.contains(value)) {
            return value;
        }
        if ("慢".equals(value) || "慢速".equals(value)) {
            return "slow";
        }
        if ("快".equals(value) || "快速".equals(value)) {
            return "fast";
        }
        return "normal";
    }

    private String defaultVoiceName(int index) {
        return index % 2 == 0 ? "软萌童声" : "机智少年音";
    }

    private String cleanDialogueText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("^[“\"']+", "")
                .replaceAll("[”\"']+$", "")
                .replaceAll("\\s+", " ");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private boolean booleanValue(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).asBoolean(false);
    }

    private String titleOf(JsonNode draft) {
        String prompt = text(draft, "prompt");
        if (StringUtils.hasText(prompt)) {
            return limit(prompt, 60);
        }
        String script = text(draft, "scriptText");
        return StringUtils.hasText(script) ? limit(script, 60) : "宠物创作视频";
    }

    private String templateTitle(JsonNode draft) {
        String templateId = text(draft, "templateId");
        return StringUtils.hasText(templateId) ? templateId : "自定义创作";
    }

    private String petTypeOf(JsonNode draft) {
        String type = text(roleAt(draft, 0), "type").toLowerCase(Locale.ROOT);
        return Set.of("cat", "dog", "other").contains(type) ? type : "other";
    }

    private String aspectRatio(JsonNode draft) {
        String ratio = text(draft, "aspectRatio", "9:16");
        return ASPECT_RATIOS.contains(ratio) ? ratio : "9:16";
    }

    private int duration(JsonNode draft) {
        int value = draft != null && draft.has("durationSeconds") ? draft.get("durationSeconds").asInt(15) : 15;
        return Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS, value));
    }

    private String normalizeIdempotency(String idempotencyKey) {
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : UUID.randomUUID().toString();
        return key.startsWith("PET_VIDEO:") ? key : "PET_VIDEO:" + key;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? LocalDateTime.now().toString() : time.toString();
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(50000, "Failed to serialize pet draft");
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
