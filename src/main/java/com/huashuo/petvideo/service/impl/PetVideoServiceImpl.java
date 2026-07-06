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
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.service.VideoAsyncTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

@Service
public class PetVideoServiceImpl implements PetVideoService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final Set<String> ASPECT_RATIOS = Set.of("9:16", "16:9", "1:1");

    private final PetVideoWorkMapper workMapper;
    private final VideoAsyncTaskService videoAsyncTaskService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final PetCreationDraftValidator draftValidator;
    private final PetVideoPromptBuilder promptBuilder;
    private final BillingEstimateService billingEstimateService;
    private final PetVideoProperties petVideoProperties;
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
        this.seedanceModelCode = seedanceModelCode;
        this.seedanceReferenceModelCode = seedanceReferenceModelCode;
    }

    @Override
    public JsonNode generateScript(JsonNode draft, Long ownerUserId) {
        ObjectNode next = normalizedDraft(draft);
        draftValidator.validateForScript(next);
        String prompt = requireTextPrompt(next);
        if (!StringUtils.hasText(text(next, "scriptText"))) {
            next.put("scriptText", buildScriptText(prompt, next));
        }
        if (!array(next, "dialogueLines").elements().hasNext()) {
            next.set("dialogueLines", buildDialogueLines(next));
        }
        return next;
    }

    @Override
    public JsonNode generateStoryboard(JsonNode draft, Long ownerUserId) {
        ObjectNode next = normalizedDraft(generateScript(draft, ownerUserId));
        draftValidator.validateForStoryboard(next);
        ArrayNode shots = objectMapper.createArrayNode();
        String backgroundPrompt = text(next.get("visualSettings"), "backgroundPrompt");
        String[] frames = {
                StringUtils.hasText(backgroundPrompt)
                        ? "主宠出现在" + backgroundPrompt + "中，保持外貌和毛色一致，建立场景氛围"
                        : "主宠出现在画面中心，保持外貌和毛色一致，建立场景氛围",
                "主宠做出明确动作或表情，第二只宠物/道具按设定参与互动",
                "用近景强化萌点、冲突点或口播重点，字幕节奏清晰",
                "镜头收束到主宠反应或故事反转，保留可二创的结尾"
        };
        int totalDuration = duration(next);
        int shotDuration = Math.max(2, Math.round(totalDuration / (float) frames.length));
        for (int i = 0; i < frames.length; i++) {
            ObjectNode shot = objectMapper.createObjectNode();
            shot.put("id", "shot-" + (i + 1));
            shot.put("index", i + 1);
            shot.put("durationSeconds", i == frames.length - 1
                    ? Math.max(2, totalDuration - shotDuration * (frames.length - 1))
                    : shotDuration);
            shot.put("frameDescription", frames[i]);
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
        return new PetVideoEstimateResponse(
                taskType,
                draftValidator.resolveGenerationMode(normalizedDraft),
                creditCost,
                billing.balance(),
                billing.balance() == null ? null : billing.balance() >= creditCost,
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
        long estimatedCredits = billing == null ? creditCost : billing.estimatedCreditCost();
        ObjectNode payloadPreview = buildProviderPayloadPreview(normalizedDraft, imageUrls, audioUrls, estimatedCredits);
        Boolean enoughBalance = billing == null ? null : billing.enoughBalance();
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
                billing == null ? "PET_DYNAMIC_ESTIMATE" : billing.pricingSource(),
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
        long creditCost = creditCostForDraft(draft, imageUrls.isEmpty() ? TaskTypeCode.SEEDANCE_TEXT_VIDEO : TaskTypeCode.SEEDANCE_REFERENCE_VIDEO);
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
        payload.set("diagnosticMetadata", buildTaskDiagnosticMetadata(draft, imageUrls, creditCost));
        return payload;
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

    private String buildScriptText(String prompt, JsonNode draft) {
        String mainRole = roleName(draft, 0, "主宠");
        String secondRole = roleName(draft, 1, "伙伴");
        return mainRole + "看向镜头，围绕“" + limit(prompt, 60) + "”做出反应。\n"
                + secondRole + "加入互动，形成一句轻松的反差或回应。\n"
                + "结尾保留一个适合短视频传播的萌点、笑点或情绪反转。";
    }

    private ArrayNode buildDialogueLines(JsonNode draft) {
        ArrayNode lines = objectMapper.createArrayNode();
        ObjectNode first = objectMapper.createObjectNode();
        first.put("id", "line-1");
        first.put("speakerRoleId", text(roleAt(draft, 0), "id", "role-main"));
        first.put("text", "你看我今天这个状态，是不是有点不一样？");
        first.put("emotion", "委屈");
        first.put("speed", "normal");
        first.put("voiceName", "cute_pet");
        first.put("lipSync", booleanValue(draft, "lipSyncEnabled"));
        lines.add(first);

        ObjectNode second = objectMapper.createObjectNode();
        second.put("id", "line-2");
        second.put("speakerRoleId", text(roleAt(draft, 1), "id", "role-second"));
        second.put("text", "不一样，今天像是准备干一件大事。");
        second.put("emotion", "惊讶");
        second.put("speed", "normal");
        second.put("voiceName", "cute_pet");
        second.put("lipSync", booleanValue(draft, "lipSyncEnabled"));
        lines.add(second);
        return lines;
    }

    private String buildSeedancePrompt(JsonNode draft) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a polished short pet video. ");
        prompt.append("Core idea: ").append(requireTextPrompt(draft)).append(". ");
        prompt.append("Style: ").append(text(draft, "style", "cute")).append(". ");
        prompt.append("Aspect ratio: ").append(aspectRatio(draft)).append(". ");
        prompt.append("Keep the pet's appearance, fur pattern, face, and body shape consistent across shots. ");
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
        String previewUrl = firstText(task == null ? null : task.outputJson(), "videoUrl");
        if (!StringUtils.hasText(previewUrl)) {
            previewUrl = work.getVideoUrl();
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
            String outputVideo = firstText(task.outputJson(), "videoUrl");
            String outputCover = firstText(task.outputJson(), "firstFrameUrl");
            if (StringUtils.hasText(outputVideo)) {
                videoUrl = outputVideo;
            }
            if (StringUtils.hasText(outputCover)) {
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
        return Math.max(5, Math.min(30, value));
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
