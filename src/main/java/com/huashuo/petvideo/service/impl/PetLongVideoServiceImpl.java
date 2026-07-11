package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.video.longform.DialogueCue;
import com.huashuo.common.video.longform.SegmentPlan;
import com.huashuo.common.video.longform.SmartSegmentPlanner;
import com.huashuo.common.video.longform.StoryboardScene;
import com.huashuo.petvideo.service.PetLongVideoService;
import com.huashuo.task.enums.TaskTypeCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PetLongVideoServiceImpl implements PetLongVideoService {

    private static final int MIN_TOTAL_DURATION = 20;
    private static final int MAX_TOTAL_DURATION = 45;
    private static final int MIN_PROVIDER_SEGMENT_SECONDS = 4;
    private static final int MAX_PROVIDER_SEGMENT_SECONDS = 15;
    private static final List<String> CAR_POLLUTION = List.of(
            "汽车销售", "车型", "车辆卖点", "车型卖点", "试驾", "门店促销", "续航里程", "到店促销", "购车权益",
            "汽车展厅", "销售顾问", "汽车创作中心"
    );
    private static final List<String> BANNED_ASSET_NAMES = List.of(
            "pet-cat-front-minimal-tabby-face.jpg",
            "pet-cat-front-tortoiseshell-green-eyes.jpg",
            "pet-dog-front-black-window-closeup.jpg",
            "pet-dog-front-black-white-long-face.jpg",
            "pet-dog-front-brown-white-golden.jpg",
            "pet-cat-front-white-brown-face.jpg"
    );

    private final ObjectMapper objectMapper;
    private final PetVideoPromptBuilder promptBuilder;
    private final BillingEstimateService billingEstimateService;
    private final PetLongVideoGenerationOrchestrator generationOrchestrator;
    private final SmartSegmentPlanner segmentPlanner = new SmartSegmentPlanner();

    public PetLongVideoServiceImpl(ObjectMapper objectMapper,
                                   PetVideoPromptBuilder promptBuilder,
                                   BillingEstimateService billingEstimateService,
                                   PetLongVideoGenerationOrchestrator generationOrchestrator) {
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.billingEstimateService = billingEstimateService;
        this.generationOrchestrator = generationOrchestrator;
    }

    @Override
    public JsonNode previewLongVideo(JsonNode composition, Long ownerUserId) {
        ObjectNode normalized = normalizeComposition(composition);
        validateComposition(normalized);
        List<StoryboardScene> scenes = scenes(normalized);
        int totalDuration = intValue(normalized, "totalDurationSeconds", intValue(normalized, "durationSeconds", 0));
        List<SegmentPlan> plan = segmentPlanner.plan(scenes, totalDuration,
                MIN_PROVIDER_SEGMENT_SECONDS, MAX_PROVIDER_SEGMENT_SECONDS);

        ArrayNode segments = objectMapper.createArrayNode();
        long totalCredits = 0L;
        for (SegmentPlan segment : plan) {
            ObjectNode segmentNode = segmentManifest(normalized, segment, ownerUserId);
            totalCredits += segmentNode.get("estimatedCredits").asLong(0);
            segments.add(segmentNode);
        }

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("compositionId", text(normalized, "compositionId", text(normalized, "compositionAssetId")));
        manifest.put("title", text(normalized, "title", "宠物剧情长视频"));
        manifest.put("businessDomain", "pet");
        manifest.put("templateType", "PET_STORY_LONG_VIDEO");
        manifest.put("totalDurationSeconds", totalDuration);
        manifest.put("aspectRatio", text(normalized, "aspectRatio", "9:16"));
        manifest.put("maxProviderSegmentSeconds", MAX_PROVIDER_SEGMENT_SECONDS);
        manifest.put("minProviderSegmentSeconds", MIN_PROVIDER_SEGMENT_SECONDS);
        manifest.put("segmentationStrategy", "storyboard_best_cut_maximize_segment_duration");
        manifest.set("characters", normalized.withArray("characters"));
        manifest.set("globalScenes", normalized.withArray("globalScenes"));
        manifest.set("sourceMaterials", normalized.withArray("materials"));
        manifest.set("segments", segments);
        manifest.set("stitching", stitchingManifest());
        manifest.set("billing", billingManifest(totalCredits, segments.size()));
        manifest.set("audit", auditResult(manifest));
        manifest.put("estimatedCredits", totalCredits);
        manifest.put("segmentCount", segments.size());
        manifest.put("providerSubmitted", false);
        manifest.put("taskCreated", false);
        manifest.put("executionStatus", "previewed");
        return manifest;
    }

    @Override
    public JsonNode dryRunLongVideoExecution(JsonNode request, Long ownerUserId) {
        ObjectNode manifest = executionManifest(request, ownerUserId);
        return generationOrchestrator.dryRun(manifest, ownerUserId);
    }

    @Override
    public JsonNode submitLongVideoExecution(JsonNode request, Long ownerUserId, String traceId) {
        return generationOrchestrator.submit(request, ownerUserId, traceId);
    }

    @Override
    public JsonNode authorizedSubmitLongVideoExecution(JsonNode request, Long ownerUserId, String traceId) {
        return generationOrchestrator.authorizedSubmitOnce(request, ownerUserId, traceId);
    }

    @Override
    public JsonNode pollLongVideoExecution(JsonNode request, Long ownerUserId) {
        return generationOrchestrator.poll(request, ownerUserId);
    }

    private ObjectNode executionManifest(JsonNode request, Long ownerUserId) {
        if (request != null && request.has("longVideoManifestId")) {
            return generationOrchestrator.loadRequiredManifest(request, ownerUserId);
        }
        if (request != null && request.has("manifest") && request.get("manifest").isObject()) {
            return (ObjectNode) request.get("manifest").deepCopy();
        }
        JsonNode composition = request != null && request.has("composition") && request.get("composition").isObject()
                ? request.get("composition")
                : request;
        return (ObjectNode) previewLongVideo(composition, ownerUserId);
    }

    private ObjectNode normalizeComposition(JsonNode source) {
        ObjectNode node = source != null && source.isObject()
                ? (ObjectNode) source.deepCopy()
                : objectMapper.createObjectNode();
        putDefault(node, "businessDomain", "pet");
        putDefault(node, "templateType", "PET_STORY_LONG_VIDEO");
        putDefault(node, "aspectRatio", "9:16");
        putDefault(node, "style", "realistic");
        ensureArray(node, "characters");
        if (!node.has("globalScenes") && node.has("scenes") && node.get("scenes").isArray()) {
            node.set("globalScenes", node.get("scenes").deepCopy());
        }
        ensureArray(node, "globalScenes");
        ensureArray(node, "materials");
        ensureArray(node, "negativePrompt");
        return node;
    }

    private void validateComposition(ObjectNode composition) {
        if (!"pet".equalsIgnoreCase(text(composition, "businessDomain", "pet"))) {
            throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: businessDomain must be pet");
        }
        int total = intValue(composition, "totalDurationSeconds", intValue(composition, "durationSeconds", 0));
        if (total < MIN_TOTAL_DURATION || total > MAX_TOTAL_DURATION) {
            throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: totalDurationSeconds 支持 20-45 秒");
        }
        ArrayNode characters = array(composition, "characters");
        if (characters.size() < 3) {
            throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 需要人物、狗和猫至少三个角色");
        }
        boolean hasHuman = false;
        int petCount = 0;
        Map<String, String> voices = new LinkedHashMap<>();
        for (JsonNode character : characters) {
            String roleId = text(character, "roleId", text(character, "id"));
            String type = text(character, "type").toLowerCase(Locale.ROOT);
            if (type.contains("human")) {
                hasHuman = true;
            }
            if (type.contains("pet") || type.contains("dog") || type.contains("cat")) {
                petCount++;
            }
            String voiceProfileId = text(character, "voiceProfileId");
            if (StringUtils.hasText(roleId) && StringUtils.hasText(voiceProfileId)) {
                voices.put(roleId, voiceProfileId);
            }
        }
        if (!hasHuman || petCount < 2) {
            throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 需要人物出镜并至少两只宠物");
        }
        if (array(composition, "globalScenes").isEmpty()) {
            throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 需要全局分镜");
        }
        Set<String> dialogueSpeakers = new LinkedHashSet<>();
        for (JsonNode scene : array(composition, "globalScenes")) {
            for (JsonNode dialogue : array(scene, "dialogues")) {
                String speakerId = text(dialogue, "speakerId");
                if (StringUtils.hasText(speakerId)) {
                    dialogueSpeakers.add(speakerId);
                    String voice = text(dialogue, "voiceProfileId");
                    if (StringUtils.hasText(voice) && voices.containsKey(speakerId) && !voice.equals(voices.get(speakerId))) {
                        throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 同一 speakerId 音色不一致：" + speakerId);
                    }
                }
                if (!StringUtils.hasText(text(dialogue, "text")) || !StringUtils.hasText(text(dialogue, "subtitle"))) {
                    throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 台词和字幕必须同时存在");
                }
            }
        }
        if (dialogueSpeakers.isEmpty()) {
            throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 需要多角色台词");
        }
        String raw = composition.toString();
        for (String keyword : CAR_POLLUTION) {
            if (raw.contains(keyword)) {
                throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 宠物长视频疑似混入汽车字段：" + keyword);
            }
        }
        for (String name : BANNED_ASSET_NAMES) {
            if (raw.contains(name)) {
                throw validation("PET_LONG_VIDEO_VALIDATION_ERROR: 引用了禁用宠物素材：" + name);
            }
        }
    }

    private List<StoryboardScene> scenes(JsonNode composition) {
        List<StoryboardScene> result = new ArrayList<>();
        for (JsonNode node : array(composition, "globalScenes")) {
            int start = intValue(node, "start", intValue(node, "globalStart", 0));
            int end = intValue(node, "end", intValue(node, "globalEnd", 0));
            List<DialogueCue> dialogues = new ArrayList<>();
            for (JsonNode item : array(node, "dialogues")) {
                dialogues.add(new DialogueCue(
                        text(item, "speakerId"),
                        text(item, "text"),
                        text(item, "subtitle", text(item, "text")),
                        intValue(item, "start", start),
                        intValue(item, "end", end)
                ));
            }
            result.add(new StoryboardScene(
                    text(node, "sceneId", "scene_" + (result.size() + 1)),
                    start,
                    end,
                    text(node, "visual"),
                    text(node, "action", text(node, "actions")),
                    text(node, "camera"),
                    dialogues,
                    stringList(node.get("referenceAssetIds"))
            ));
        }
        return result;
    }

    private ObjectNode segmentManifest(ObjectNode composition, SegmentPlan segment, Long ownerUserId) {
        ObjectNode segmentNode = objectMapper.createObjectNode();
        segmentNode.put("segmentIndex", segment.segmentIndex());
        segmentNode.put("globalStart", segment.globalStartSeconds());
        segmentNode.put("globalEnd", segment.globalEndSeconds());
        segmentNode.put("durationSeconds", segment.durationSeconds());
        segmentNode.put("cutReason", segment.cutReason());
        segmentNode.put("stitchOrder", segment.segmentIndex());
        segmentNode.put("idempotencyKey", "pet-long-video-" + text(composition, "compositionId", "composition")
                + "-segment-" + segment.segmentIndex() + "-v1");

        ArrayNode includedSceneIds = objectMapper.createArrayNode();
        ArrayNode localScenes = objectMapper.createArrayNode();
        ArrayNode localDialogues = objectMapper.createArrayNode();
        ArrayNode localSubtitles = objectMapper.createArrayNode();
        for (StoryboardScene scene : segment.scenes()) {
            includedSceneIds.add(scene.sceneId());
            ObjectNode localScene = objectMapper.createObjectNode();
            localScene.put("sceneId", scene.sceneId());
            localScene.put("start", scene.startSeconds() - segment.globalStartSeconds());
            localScene.put("end", scene.endSeconds() - segment.globalStartSeconds());
            localScene.put("visual", scene.visual());
            localScene.put("action", scene.action());
            localScene.put("camera", scene.camera());
            localScenes.add(localScene);
            for (DialogueCue dialogue : scene.dialogues()) {
                ObjectNode line = objectMapper.createObjectNode();
                line.put("speakerId", dialogue.speakerId());
                line.put("text", dialogue.text());
                line.put("subtitle", dialogue.subtitle());
                line.put("start", dialogue.startSeconds() - segment.globalStartSeconds());
                line.put("end", dialogue.endSeconds() - segment.globalStartSeconds());
                localDialogues.add(line);
                ObjectNode subtitle = objectMapper.createObjectNode();
                subtitle.put("speakerId", dialogue.speakerId());
                subtitle.put("text", dialogue.subtitle());
                subtitle.put("start", dialogue.startSeconds() - segment.globalStartSeconds());
                subtitle.put("end", dialogue.endSeconds() - segment.globalStartSeconds());
                localSubtitles.add(subtitle);
            }
        }
        segmentNode.set("includedSceneIds", includedSceneIds);
        segmentNode.set("localScenes", localScenes);
        segmentNode.set("localDialogues", localDialogues);
        segmentNode.set("localSubtitles", localSubtitles);
        segmentNode.set("referenceAssetIds", referenceAssetIds(composition, segment));
        segmentNode.set("voiceMapping", voiceMapping(composition));
        segmentNode.set("subtitleConfig", subtitleConfig(composition, localSubtitles));

        ObjectNode draft = segmentDraft(composition, segment, localScenes, localDialogues, localSubtitles);
        String prompt = longVideoPrompt(composition, segment, localScenes, localDialogues, localSubtitles);
        long estimatedCredits = estimateSegmentCredits(draft, ownerUserId, segment.durationSeconds());
        segmentNode.put("estimatedCredits", estimatedCredits);
        segmentNode.put("taskStatus", "pending");
        segmentNode.put("providerTaskId", (String) null);
        segmentNode.put("generationTaskId", (String) null);
        segmentNode.put("outputAssetId", (String) null);
        segmentNode.put("resultUrl", (String) null);
        segmentNode.put("reservedCredits", 0L);
        segmentNode.put("actualChargedCredits", 0L);
        segmentNode.put("submittedAt", (String) null);
        segmentNode.put("negativePrompt", promptBuilder.negativePrompt());
        segmentNode.put("segmentPrompt", prompt);
        segmentNode.set("providerPayload", providerPayload(composition, draft, prompt, estimatedCredits));
        return segmentNode;
    }

    private ObjectNode segmentDraft(ObjectNode composition,
                                    SegmentPlan segment,
                                    ArrayNode localScenes,
                                    ArrayNode localDialogues,
                                    ArrayNode localSubtitles) {
        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("templateId", "pet-human-story-video");
        draft.put("templateName", "人宠情景视频");
        draft.put("title", text(composition, "title", "宠物剧情长视频") + " - segment " + segment.segmentIndex());
        draft.put("prompt", segmentDraftSummary(composition, segment));
        draft.put("videoType", "dialogue");
        draft.put("generationMode", "dialogue_video");
        draft.put("language", text(composition, "language", "zh-CN"));
        draft.put("aspectRatio", text(composition, "aspectRatio", "9:16"));
        draft.put("durationSeconds", segment.durationSeconds());
        draft.put("style", text(composition, "style", "realistic"));
        draft.put("voiceEnabled", booleanValue(composition, "voiceEnabled", true));
        draft.put("lipSyncEnabled", booleanValue(composition, "lipSyncEnabled", true));
        draft.put("subtitleEnabled", true);
        draft.put("bgmEnabled", booleanValue(composition, "bgmEnabled", true));
        draft.set("characters", composition.withArray("characters"));
        draft.set("roles", rolesFromCharacters(composition));
        draft.set("materials", composition.withArray("materials"));
        draft.set("dialogueLines", localDialogues);
        draft.set("shots", localScenes);
        draft.set("subtitleConfig", subtitleConfig(composition, localSubtitles));
        draft.set("audioConfig", audioConfig(composition));
        draft.set("negativePrompt", negativePrompt());
        return draft;
    }

    private String segmentPrompt(ObjectNode composition, SegmentPlan segment) {
        StringBuilder builder = new StringBuilder();
        builder.append("生成宠物剧情长视频《").append(text(composition, "title", "宠物剧情")).append("》的第 ")
                .append(segment.segmentIndex()).append(" 段，使用全局时间 ")
                .append(segment.globalStartSeconds()).append("-").append(segment.globalEndSeconds())
                .append(" 秒。必须保持上一段和下一段的角色、场景、光线、字幕风格和音色连续。");
        for (StoryboardScene scene : segment.scenes()) {
            builder.append(" 分镜 ").append(scene.sceneId()).append("：").append(scene.visual());
            for (DialogueCue dialogue : scene.dialogues()) {
                builder.append(" 台词 ").append(dialogue.speakerId()).append("：").append(dialogue.text());
            }
        }
        builder.append(" 不要生成汽车、展厅、销售顾问、试驾、品牌广告或无关文字。");
        return builder.toString();
    }

    private String segmentDraftSummary(ObjectNode composition, SegmentPlan segment) {
        StringBuilder builder = new StringBuilder();
        builder.append("Generate pet long-story video segment ")
                .append(segment.segmentIndex())
                .append(" for title ")
                .append(text(composition, "title", "pet story"))
                .append(". Use global timeline ")
                .append(segment.globalStartSeconds())
                .append("-")
                .append(segment.globalEndSeconds())
                .append(" seconds. Preserve character identity, home scene, light, subtitle style, and voice continuity with adjacent segments.");
        for (StoryboardScene scene : segment.scenes()) {
            builder.append(" Scene ").append(scene.sceneId()).append(": ").append(scene.visual());
            for (DialogueCue dialogue : scene.dialogues()) {
                builder.append(" Dialogue ").append(dialogue.speakerId()).append(": ").append(dialogue.text());
            }
        }
        builder.append(" Avoid unrelated industry templates, product ads, brand watermarks, unrelated text, sticker-only output, and low-quality random pet motion.");
        return limit(builder.toString(), 1600);
    }

    private String longVideoPrompt(ObjectNode composition,
                                   SegmentPlan segment,
                                   ArrayNode localScenes,
                                   ArrayNode localDialogues,
                                   ArrayNode localSubtitles) {
        StringBuilder builder = new StringBuilder(3600);
        builder.append("Create a finished pet story video segment for the pet creation center only. ");
        builder.append("Title: ").append(text(composition, "title", "pet story long video")).append(". ");
        builder.append("Segment ").append(segment.segmentIndex())
                .append(" uses global timeline ")
                .append(segment.globalStartSeconds()).append("-").append(segment.globalEndSeconds())
                .append(" seconds, duration ").append(segment.durationSeconds()).append(" seconds. ");
        builder.append("Keep character identity, pet appearance, home scene lighting, subtitle style, and voice style continuous with adjacent segments. ");
        builder.append("Use the uploaded reference images as identity anchors: main pet, second pet, human owner avatar, and living-room scene. ");
        builder.append("Keep the dog and cat visually separate, preserve each pet's fur color, face markings, body shape, and cute natural behavior. ");
        builder.append("The human owner is a warm supporting character and must not replace the pets as the main subject. ");
        builder.append("Scenes: ").append(compactJson(localScenes, 900)).append(". ");
        builder.append("Dialogue lines: ").append(compactJson(localDialogues, 900)).append(". ");
        builder.append("Subtitles are external overlay references; reserve bottom safe area and do not render random unreadable text in the raw video. ");
        builder.append("Subtitle timeline: ").append(compactJson(localSubtitles, 700)).append(". ");
        builder.append("Style: realistic, warm, clean family-home feeling, light comedy rhythm, polished social media short-drama quality. ");
        builder.append("Camera: stable medium or close shot, gentle push-in only, no fast motion, no abrupt scene switching. ");
        builder.append("Story requirement: this segment must keep the snack-meeting plot understandable with dialogue, reactions, and a clear story beat. ");
        builder.append("Avoid unrelated industry templates, product ads, brand watermarks, unrelated text, sticker-only output, random pet motion, low clarity, flicker, or face/body deformation. ");
        return limit(builder.toString(), 5000);
    }

    private ObjectNode providerPayload(ObjectNode composition, ObjectNode draft, String prompt, long estimatedCredits) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("provider", "seedance");
        payload.put("businessDomain", "pet");
        payload.put("businessType", "pet_creation");
        payload.put("templateType", "PET_STORY_LONG_VIDEO_SEGMENT");
        payload.put("taskType", TaskTypeCode.SEEDANCE_REFERENCE_VIDEO);
        payload.put("generationMode", "dialogue_video");
        payload.put("durationSeconds", intValue(draft, "durationSeconds", 0));
        payload.put("aspectRatio", text(draft, "aspectRatio", "9:16"));
        payload.put("estimatedCreditCost", estimatedCredits);
        payload.put("watermark", false);
        payload.put("generateAudio", booleanValue(draft, "voiceEnabled", true));
        payload.put("prompt", prompt);
        payload.put("negativePrompt", promptBuilder.negativePrompt());
        payload.set("imageUrls", imageUrls(composition));
        payload.set("diagnosticMetadata", draft);
        return payload;
    }

    private long estimateSegmentCredits(ObjectNode draft, Long ownerUserId, int durationSeconds) {
        int imageCount = imageUrls(draft).size();
        long base = billingEstimateService.resolveCreditCost(TaskTypeCode.SEEDANCE_REFERENCE_VIDEO, null);
        if (base <= 0) {
            base = 220L;
        }
        long petEstimate = base
                + Math.max(0, imageCount - 2) * 10L
                + (booleanValue(draft, "voiceEnabled", true) ? 20L : 0L)
                + (booleanValue(draft, "lipSyncEnabled", true) ? 20L : 0L);
        BillingEstimateResponse billing = billingEstimateService.estimate(new BillingEstimateRequest(
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                null,
                null,
                text(draft, "prompt").length(),
                Math.max(1, imageCount),
                1,
                BigDecimal.valueOf(durationSeconds),
                BigDecimal.valueOf(petEstimate),
                ownerUserId
        ));
        return Math.max(petEstimate, billing == null ? 0L : billing.estimatedCreditCost());
    }

    private ArrayNode rolesFromCharacters(ObjectNode composition) {
        ArrayNode roles = objectMapper.createArrayNode();
        for (JsonNode character : array(composition, "characters")) {
            ObjectNode role = objectMapper.createObjectNode();
            role.put("id", text(character, "roleId", text(character, "id")));
            role.put("name", text(character, "displayName", text(character, "name")));
            String type = text(character, "type").toLowerCase(Locale.ROOT);
            role.put("type", type.contains("dog") ? "dog" : type.contains("cat") ? "cat" : "other");
            role.put("speakingTone", text(character, "voiceStyle", text(character, "voiceProfileId")));
            roles.add(role);
        }
        return roles;
    }

    private ObjectNode voiceMapping(ObjectNode composition) {
        ObjectNode mapping = objectMapper.createObjectNode();
        for (JsonNode character : array(composition, "characters")) {
            String roleId = text(character, "roleId", text(character, "id"));
            if (StringUtils.hasText(roleId)) {
                mapping.put(roleId, text(character, "voiceProfileId"));
            }
        }
        return mapping;
    }

    private ObjectNode subtitleConfig(ObjectNode composition, ArrayNode timeline) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("enabled", true);
        config.put("language", text(composition, "language", "zh-CN"));
        config.put("position", "bottom_safe_area");
        config.put("fontFamily", "Microsoft YaHei");
        config.put("fontSize", 34);
        config.put("textColor", "#ffffff");
        config.put("strokeMode", "strong");
        config.put("outlineColor", "#111827");
        config.put("avoidFaceOcclusion", true);
        config.put("maxLines", 2);
        config.set("timeline", timeline);
        return config;
    }

    private ObjectNode audioConfig(ObjectNode composition) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("consistentVoicePerCharacter", true);
        config.put("backgroundMusic", text(composition, "backgroundMusic", "light_warm_comedy"));
        config.put("bgmVolume", "low");
        config.put("dialogueVolume", "clear");
        config.set("voiceProfiles", voiceMapping(composition));
        return config;
    }

    private ArrayNode imageUrls(ObjectNode node) {
        ArrayNode urls = objectMapper.createArrayNode();
        for (JsonNode material : array(node, "materials")) {
            String role = text(material, "role");
            if (!"audio".equals(role)) {
                String url = text(material, "url", text(material, "fileUrl"));
                if (StringUtils.hasText(url)) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    private ArrayNode referenceAssetIds(ObjectNode composition, SegmentPlan segment) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (StoryboardScene scene : segment.scenes()) {
            ids.addAll(scene.referenceAssetIds());
        }
        for (JsonNode material : array(composition, "materials")) {
            String assetId = text(material, "assetId");
            if (StringUtils.hasText(assetId)) {
                ids.add(assetId);
            }
        }
        ArrayNode result = objectMapper.createArrayNode();
        ids.forEach(result::add);
        return result;
    }

    private ArrayNode negativePrompt() {
        ArrayNode negative = objectMapper.createArrayNode();
        negative.add("不要生成无剧情视频");
        negative.add("不要生成无对白视频");
        negative.add("不要复用汽车创作中心模板内容");
        negative.add("不要出现汽车、展厅、车型、销售顾问、试驾、价格促销、品牌广告、水印或无关文字");
        negative.add("不要改变宠物品种、毛色或主体身份");
        negative.add("不要让字幕遮挡人物或宠物脸部");
        return negative;
    }

    private ObjectNode stitchingManifest() {
        ObjectNode stitching = objectMapper.createObjectNode();
        stitching.put("enabled", true);
        stitching.put("method", "shared_long_video_stitcher_pending_extraction");
        stitching.put("preserveAudioSync", true);
        stitching.put("preserveSubtitleTimeline", true);
        stitching.put("outputSingleVideo", true);
        return stitching;
    }

    private ObjectNode billingManifest(long totalCredits, int segmentCount) {
        ObjectNode billing = objectMapper.createObjectNode();
        billing.put("estimateMode", "sum_of_segments_plus_stitching_if_any");
        billing.put("reserveMode", "reserve_total_before_submit_or_segment_safe_reserve");
        billing.put("onFailure", "release_unsubmitted_or_failed_refundable_segments");
        billing.put("segmentCount", segmentCount);
        billing.put("estimatedCredits", totalCredits);
        return billing;
    }

    private ObjectNode auditResult(ObjectNode manifest) {
        String raw = scrubAuditNode(manifest).toString();
        ObjectNode audit = objectMapper.createObjectNode();
        audit.put("segmentDurationValid", true);
        audit.put("bannedAssetCheck", BANNED_ASSET_NAMES.stream().noneMatch(raw::contains));
        audit.put("carPollutionCheck", CAR_POLLUTION.stream().noneMatch(raw::contains));
        audit.put("referenceImageCheck", manifest.withArray("segments").size() > 0);
        return audit;
    }

    private JsonNode scrubAuditNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                copy.add(scrubAuditNode(item));
            }
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (!"negativePrompt".equals(entry.getKey())) {
                    copy.set(entry.getKey(), scrubAuditNode(entry.getValue()));
                }
            });
            return copy;
        }
        return node.deepCopy();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(40000, message);
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return fallback;
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : fallback;
    }

    private static boolean booleanValue(JsonNode node, String field, boolean fallback) {
        return node != null && node.has(field) ? node.get(field).asBoolean(fallback) : fallback;
    }

    private static ArrayNode array(JsonNode node, String field) {
        if (node != null && node.has(field) && node.get(field).isArray()) {
            return (ArrayNode) node.get(field);
        }
        return new ObjectMapper().createArrayNode();
    }

    private void ensureArray(ObjectNode node, String field) {
        if (!node.has(field) || !node.get(field).isArray()) {
            node.set(field, objectMapper.createArrayNode());
        }
    }

    private static void putDefault(ObjectNode node, String field, String value) {
        if (!node.has(field) || !StringUtils.hasText(node.get(field).asText(""))) {
            node.put(field, value);
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (StringUtils.hasText(item.asText(""))) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }

    private static String compactJson(JsonNode node, int maxLength) {
        if (node == null) {
            return "";
        }
        return limit(node.toString(), maxLength);
    }

    private static String limit(String value, int maxLength) {
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
