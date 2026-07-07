package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class PetCreationDraftValidator {

    public static final String MODE_REFERENCE_VIDEO = "reference_video";
    public static final String MODE_TEXT_VIDEO = "text_video";
    public static final String MODE_DIALOGUE_VIDEO = "dialogue_video";
    public static final String MODE_IMAGE_TO_VIDEO = "image_to_video";

    private static final Set<String> VIDEO_TYPES = Set.of("dialogue", "short_drama", "monologue", "talking", "image_to_video", "sticker");
    private static final Set<String> GENERATION_MODES = Set.of(MODE_REFERENCE_VIDEO, MODE_TEXT_VIDEO, MODE_DIALOGUE_VIDEO, MODE_IMAGE_TO_VIDEO);
    private static final Set<String> MATERIAL_ROLES = Set.of("main_pet", "second_pet", "prop", "scene", "audio");
    private static final Set<String> PET_TYPES = Set.of("cat", "dog", "other");
    private static final Set<String> ASPECT_RATIOS = Set.of("9:16", "16:9", "1:1");
    private static final Set<Integer> DURATIONS = Set.of(5, 10, 15, 30);
    private static final Set<String> STYLES = Set.of("realistic", "cute", "anime", "anthropomorphic", "funny", "healing");
    private static final Set<String> PLACEHOLDERS = Set.of("请输入", "描述你想要的宠物视频", "test", "demo", "placeholder", "测试");
    private static final List<String> CAR_POLLUTION = List.of("汽车销售", "车型", "车辆卖点", "车型卖点", "试驾", "门店促销", "续航里程", "到店促销", "购车权益");

    private final ObjectMapper objectMapper;

    public PetCreationDraftValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode normalize(JsonNode draft) {
        ObjectNode node = draft != null && draft.isObject()
                ? (ObjectNode) draft.deepCopy()
                : objectMapper.createObjectNode();
        putDefault(node, "videoType", "dialogue");
        putDefault(node, "language", "zh-CN");
        putDefault(node, "aspectRatio", "9:16");
        putDefault(node, "style", "cute");
        if (!node.has("durationSeconds") || node.get("durationSeconds").asInt(0) <= 0) {
            node.put("durationSeconds", 15);
        }
        ensureArray(node, "roles");
        ensureArray(node, "materials");
        ensureArray(node, "dialogueLines");
        ensureArray(node, "shots");
        String mode = resolveGenerationMode(node);
        if (StringUtils.hasText(mode)) {
            node.put("generationMode", mode);
        }
        return node;
    }

    public void validateForScript(ObjectNode draft) {
        validatePrompt(draft, 1);
        rejectCarPollution(draft);
        validateBaseParams(draft);
    }

    public void validateForStoryboard(ObjectNode draft) {
        validatePrompt(draft, 10);
        rejectCarPollution(draft);
        validateBaseParams(draft);
        validateRoles(draft, false);
    }

    public void validateForTask(ObjectNode draft) {
        validatePrompt(draft, 10);
        rejectCarPollution(draft);
        validateBaseParams(draft);
        validateMaterials(draft);
        validateRoles(draft, true);
        validateDialogue(draft);
        validateStoryboard(draft);
        validateAudioSubtitle(draft);
    }

    public String resolveGenerationMode(JsonNode draft) {
        String explicit = text(draft, "generationMode");
        if (StringUtils.hasText(explicit)) {
            String normalized = explicit.trim().toLowerCase(Locale.ROOT);
            if (!GENERATION_MODES.contains(normalized)) {
                throw validation("PET_VALIDATION_ERROR: generationMode 不支持：" + explicit);
            }
            return normalized;
        }
        if ("image_to_video".equals(text(draft, "videoType"))) {
            return MODE_IMAGE_TO_VIDEO;
        }
        if (hasMainPetMaterial(draft)) {
            return "dialogue".equals(text(draft, "videoType")) ? MODE_DIALOGUE_VIDEO : MODE_REFERENCE_VIDEO;
        }
        return "";
    }

    public boolean hasMainPetMaterial(JsonNode draft) {
        for (JsonNode material : array(draft, "materials")) {
            if ("main_pet".equals(text(material, "role")) && hasLocator(material)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSecondPetMaterial(JsonNode draft) {
        for (JsonNode material : array(draft, "materials")) {
            if ("second_pet".equals(text(material, "role")) && hasLocator(material)) {
                return true;
            }
        }
        return false;
    }

    public int countReferenceImages(JsonNode draft) {
        int count = 0;
        for (JsonNode material : array(draft, "materials")) {
            if (!"audio".equals(text(material, "role")) && hasLocator(material)) {
                count++;
            }
        }
        return count;
    }

    public List<String> warnings(JsonNode draft) {
        List<String> warnings = new ArrayList<>();
        String mode = resolveGenerationMode(draft);
        if (MODE_TEXT_VIDEO.equals(mode) && !hasMainPetMaterial(draft)) {
            warnings.add("未提供主宠物素材，将使用纯文本生成，角色一致性会降低。");
        }
        if (booleanValue(draft, "subtitleEnabled")) {
            warnings.add("字幕会作为生成建议写入任务；真实烧录字幕仍依赖后端字幕后处理能力。");
        }
        if (booleanValue(draft, "bgmEnabled") && referenceAudioUrls(draft) == 0) {
            warnings.add("未选择 BGM 时使用默认背景音乐策略，不会把 BGM 当作口播音频。");
        }
        if (("dialogue".equals(text(draft, "videoType")) || MODE_DIALOGUE_VIDEO.equals(mode)) && !hasSecondPetMaterial(draft)) {
            warnings.add("多宠物对话缺少第二或更多宠物参考图，非主角角色一致性会下降。");
        }
        return warnings;
    }

    private void validatePrompt(JsonNode draft, int minLength) {
        String prompt = text(draft, "prompt");
        if (!StringUtils.hasText(prompt)) {
            throw validation("PET_VALIDATION_ERROR: 宠物视频生成需要填写创作需求");
        }
        if (prompt.length() < minLength) {
            throw validation("PET_VALIDATION_ERROR: 创作需求至少需要 " + minLength + " 个字");
        }
        if (prompt.length() > 500) {
            throw validation("PET_VALIDATION_ERROR: 创作需求不能超过 500 字");
        }
        for (String placeholder : PLACEHOLDERS) {
            if (prompt.equalsIgnoreCase(placeholder) || prompt.contains(placeholder)) {
                throw validation("PET_VALIDATION_ERROR: 创作需求不能使用占位示例");
            }
        }
    }

    private void rejectCarPollution(JsonNode draft) {
        String raw = draft.toString();
        for (String keyword : CAR_POLLUTION) {
            if (raw.contains(keyword)) {
                throw validation("PET_VALIDATION_ERROR: 宠物请求疑似混入车辆创作字段：" + keyword);
            }
        }
    }

    private void validateBaseParams(JsonNode draft) {
        if (!VIDEO_TYPES.contains(text(draft, "videoType"))) {
            throw validation("PET_VALIDATION_ERROR: videoType 不支持");
        }
        if (!ASPECT_RATIOS.contains(text(draft, "aspectRatio"))) {
            throw validation("PET_VALIDATION_ERROR: aspectRatio 仅支持 9:16、16:9、1:1");
        }
        if (!DURATIONS.contains(duration(draft))) {
            throw validation("PET_VALIDATION_ERROR: durationSeconds 仅支持 5、10、15、30");
        }
        if (!STYLES.contains(text(draft, "style"))) {
            throw validation("PET_VALIDATION_ERROR: style 不支持");
        }
        String backgroundPrompt = text(draft == null ? null : draft.get("visualSettings"), "backgroundPrompt");
        if (backgroundPrompt.length() > 160) {
            throw validation("PET_VALIDATION_ERROR: 背景图/场景要求不能超过 160 字");
        }
        String productPrompt = text(draft == null ? null : draft.get("visualSettings"), "productPrompt");
        if (productPrompt.length() > 160) {
            throw validation("PET_VALIDATION_ERROR: 产品/道具展示要求不能超过 160 字");
        }
    }

    private void validateMaterials(JsonNode draft) {
        String mode = resolveGenerationMode(draft);
        if (!MODE_TEXT_VIDEO.equals(mode) && !hasMainPetMaterial(draft)) {
            throw validation("PET_MATERIAL_INVALID: 请先添加主宠物参考图，或明确选择 text_video 纯文本生成模式");
        }
        if (!StringUtils.hasText(mode)) {
            throw validation("PET_VALIDATION_ERROR: 未提供主宠物素材时必须显式设置 generationMode=text_video");
        }

        int mainPet = 0;
        int secondPet = 0;
        int prop = 0;
        int scene = 0;
        int audio = 0;
        Set<String> seen = new HashSet<>();
        for (JsonNode material : array(draft, "materials")) {
            String role = text(material, "role");
            if (!MATERIAL_ROLES.contains(role)) {
                throw validation("PET_MATERIAL_INVALID: 素材 role 不支持：" + role);
            }
            if (!hasLocator(material)) {
                throw validation("PET_MATERIAL_INVALID: 素材缺少 URL 或 assetId");
            }
            String url = text(material, "url");
            if (StringUtils.hasText(url) && !validUrl(url)) {
                throw validation("PET_MATERIAL_INVALID: 素材 URL 不可识别");
            }
            String key = role + ":" + text(material, "assetId", url);
            if (!seen.add(key)) {
                throw validation("PET_MATERIAL_INVALID: 素材重复添加");
            }
            switch (role) {
                case "main_pet" -> mainPet++;
                case "second_pet" -> secondPet++;
                case "prop" -> prop++;
                case "scene" -> scene++;
                case "audio" -> audio++;
                default -> {
                }
            }
        }
        if (mainPet > 3) throw validation("PET_MATERIAL_INVALID: 主宠物素材最多 3 张");
        if (secondPet > 3) throw validation("PET_MATERIAL_INVALID: 第二或更多宠物素材最多 3 张");
        if (prop > 4) throw validation("PET_MATERIAL_INVALID: 产品/道具素材最多 4 张");
        if (scene > 4) throw validation("PET_MATERIAL_INVALID: 场景素材最多 4 张");
        if (audio > 1) throw validation("PET_MATERIAL_INVALID: 音频素材最多 1 条");
    }

    private void validateRoles(JsonNode draft, boolean strict) {
        ArrayNode roles = array(draft, "roles");
        if (roles.isEmpty()) {
            throw validation("PET_VALIDATION_ERROR: 至少需要一个宠物角色");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode role : roles) {
            String name = text(role, "name");
            if (!StringUtils.hasText(name)) {
                throw validation("PET_VALIDATION_ERROR: 宠物角色名称不能为空");
            }
            if (!names.add(name)) {
                throw validation("PET_VALIDATION_ERROR: 宠物角色名称不能重复：" + name);
            }
            String type = text(role, "type");
            if (!PET_TYPES.contains(type)) {
                throw validation("PET_VALIDATION_ERROR: 宠物类型不支持：" + type);
            }
            if (strict && array(role, "personalityTags").isEmpty()) {
                throw validation("PET_VALIDATION_ERROR: 角色「" + name + "」至少需要一个性格标签");
            }
            if (strict && !StringUtils.hasText(text(role, "speakingTone"))) {
                throw validation("PET_VALIDATION_ERROR: 角色「" + name + "」需要设置说话口吻");
            }
        }
        String mode = resolveGenerationMode(draft);
        if (strict && (MODE_DIALOGUE_VIDEO.equals(mode) || "dialogue".equals(text(draft, "videoType"))) && roles.size() < 2) {
            throw validation("PET_VALIDATION_ERROR: 宠物对话视频至少需要两个宠物角色，也可以继续添加更多角色");
        }
    }

    private void validateDialogue(JsonNode draft) {
        boolean requiresDialogue = booleanValue(draft, "voiceEnabled")
                || booleanValue(draft, "lipSyncEnabled")
                || MODE_DIALOGUE_VIDEO.equals(resolveGenerationMode(draft))
                || "dialogue".equals(text(draft, "videoType"));
        ArrayNode lines = array(draft, "dialogueLines");
        if (requiresDialogue && countTextLines(lines) == 0) {
            throw validation("PET_VALIDATION_ERROR: 当前配置需要至少一条有效台词");
        }
        Set<String> roleIds = new HashSet<>();
        for (JsonNode role : array(draft, "roles")) {
            roleIds.add(text(role, "id"));
        }
        for (JsonNode line : lines) {
            String text = text(line, "text");
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (!roleIds.contains(text(line, "speakerRoleId"))) {
                throw validation("PET_VALIDATION_ERROR: 台词没有匹配到说话角色");
            }
            if (text.length() > 80) {
                throw validation("PET_VALIDATION_ERROR: 单条台词不能超过 80 字");
            }
        }
    }

    private void validateStoryboard(JsonNode draft) {
        ArrayNode shots = array(draft, "shots");
        if (shots.isEmpty()) {
            throw validation("PET_VALIDATION_ERROR: 正式生成需要先生成或编辑分镜");
        }
        if (shots.size() < 3) {
            throw validation("PET_VALIDATION_ERROR: 正式生成至少需要 3 个分镜");
        }
        if (shots.size() > 8) {
            throw validation("PET_VALIDATION_ERROR: 分镜最多 8 个");
        }
        int total = 0;
        for (JsonNode shot : shots) {
            int seconds = shot.has("durationSeconds") ? shot.get("durationSeconds").asInt(0) : 0;
            total += seconds;
            int index = shot.has("index") ? shot.get("index").asInt(0) : 0;
            if (seconds < 1 || seconds > 8) {
                throw validation("PET_VALIDATION_ERROR: 镜头 " + index + " 时长必须在 1-8 秒之间");
            }
            if (!StringUtils.hasText(text(shot, "frameDescription"))) {
                throw validation("PET_VALIDATION_ERROR: 镜头 " + index + " 缺少画面描述");
            }
            if (!StringUtils.hasText(text(shot, "characterAction"))) {
                throw validation("PET_VALIDATION_ERROR: 镜头 " + index + " 缺少角色动作");
            }
            if (!StringUtils.hasText(text(shot, "cameraMove"))) {
                throw validation("PET_VALIDATION_ERROR: 镜头 " + index + " 缺少运镜方式");
            }
            if (booleanValue(draft, "subtitleEnabled") && !StringUtils.hasText(text(shot, "subtitle"))) {
                throw validation("PET_VALIDATION_ERROR: 镜头 " + index + " 开启字幕但缺少字幕文本");
            }
            if (text(shot, "subtitle").length() > 36) {
                throw validation("PET_VALIDATION_ERROR: 镜头 " + index + " 字幕不能超过 36 字");
            }
        }
        int target = duration(draft);
        if (Math.abs(total - target) > Math.max(3, Math.round(target * 0.35f))) {
            throw validation("PET_VALIDATION_ERROR: 分镜总时长 " + total + " 秒与目标 " + target + " 秒差距过大");
        }
    }

    private void validateAudioSubtitle(JsonNode draft) {
        int dialogueCount = countTextLines(array(draft, "dialogueLines"));
        boolean hasScript = StringUtils.hasText(text(draft, "scriptText"));
        if (booleanValue(draft, "lipSyncEnabled") && !booleanValue(draft, "voiceEnabled")) {
            throw validation("PET_VALIDATION_ERROR: 开启口型同步时必须开启配音");
        }
        if (booleanValue(draft, "lipSyncEnabled") && dialogueCount == 0) {
            throw validation("PET_VALIDATION_ERROR: 开启口型同步时必须存在有效台词");
        }
        if (booleanValue(draft, "voiceEnabled") && dialogueCount == 0 && !hasScript) {
            throw validation("PET_VALIDATION_ERROR: 开启配音时需要台词或脚本文案");
        }
        if (booleanValue(draft, "subtitleEnabled") && dialogueCount == 0 && !hasScript && !hasShotSubtitle(draft)) {
            throw validation("PET_VALIDATION_ERROR: 开启字幕时至少需要脚本、台词或分镜字幕");
        }
    }

    private int countTextLines(ArrayNode lines) {
        int count = 0;
        for (JsonNode line : lines) {
            if (StringUtils.hasText(text(line, "text"))) {
                count++;
            }
        }
        return count;
    }

    private boolean hasShotSubtitle(JsonNode draft) {
        for (JsonNode shot : array(draft, "shots")) {
            if (StringUtils.hasText(text(shot, "subtitle"))) {
                return true;
            }
        }
        return false;
    }

    private int referenceAudioUrls(JsonNode draft) {
        int count = 0;
        for (JsonNode material : array(draft, "materials")) {
            if ("audio".equals(text(material, "role")) && hasLocator(material)) {
                count++;
            }
        }
        return count;
    }

    private void putDefault(ObjectNode node, String field, String value) {
        if (!StringUtils.hasText(text(node, field))) {
            node.put(field, value);
        }
    }

    private void ensureArray(ObjectNode node, String field) {
        if (!node.has(field) || !node.get(field).isArray()) {
            node.set(field, objectMapper.createArrayNode());
        }
    }

    private boolean hasLocator(JsonNode material) {
        return StringUtils.hasText(text(material, "url")) || StringUtils.hasText(text(material, "assetId"));
    }

    private boolean validUrl(String url) {
        String value = url.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("asset://") || value.startsWith("tos://") || value.startsWith("/assets/") || value.startsWith("/uploads/")) {
            return true;
        }
        try {
            URI uri = URI.create(url);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    private ArrayNode array(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return objectMapper.createArrayNode();
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

    private int duration(JsonNode draft) {
        return draft != null && draft.has("durationSeconds") ? draft.get("durationSeconds").asInt(15) : 15;
    }

    private BusinessException validation(String message) {
        return new BusinessException(40000, message);
    }
}
