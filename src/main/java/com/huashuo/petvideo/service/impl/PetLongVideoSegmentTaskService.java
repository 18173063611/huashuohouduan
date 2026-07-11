package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.service.PetVideoService;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.OptionalLong;

@Service
public class PetLongVideoSegmentTaskService {

    private final PetVideoService petVideoService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public PetLongVideoSegmentTaskService(PetVideoService petVideoService,
                                          TaskService taskService,
                                          ObjectMapper objectMapper) {
        this.petVideoService = petVideoService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    public ObjectNode submitSegment(ObjectNode segment, Long ownerUserId, String traceId) {
        if (hasTaskId(segment)) {
            return segment;
        }
        ObjectNode draft = draftForSubmit(segment);
        String idempotencyKey = text(segment, "idempotencyKey");
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_SEGMENT_INVALID: idempotencyKey is required");
        }
        PetVideoTaskResponse created = petVideoService.createTask(draft, ownerUserId, traceId, idempotencyKey);
        if (created == null || !StringUtils.hasText(created.id())) {
            throw new BusinessException(50000, "PET_LONG_VIDEO_SEGMENT_SUBMIT_FAILED: task id is empty");
        }
        Long taskId = parseTaskId(created.id());
        segment.put("generationTaskId", taskId);
        segment.put("providerTaskId", created.id());
        segment.put("taskStatus", "submitted");
        segment.put("submittedAt", LocalDateTime.now().toString());
        segment.put("reservedCredits", longValue(segment, "estimatedCredits", 0L));
        segment.put("actualChargedCredits", 0L);
        segment.set("submitResponse", objectMapper.valueToTree(created));
        return segment;
    }

    public ObjectNode pollSegment(ObjectNode segment, Long ownerUserId) {
        Long taskId = taskId(segment);
        if (taskId == null) {
            segment.put("taskStatus", "pending");
            return segment;
        }
        TaskItem task = taskService.getTaskForViewer(taskId, viewer(ownerUserId));
        String status = longVideoStatus(task == null ? null : task.status());
        segment.put("generationTaskId", taskId);
        segment.put("providerTaskId", String.valueOf(taskId));
        segment.put("taskStatus", status);
        segment.put("polledAt", LocalDateTime.now().toString());
        if (task != null) {
            if (task.estimatedCreditCost() != null) {
                segment.put("reservedCredits", task.estimatedCreditCost());
            }
            if (task.actualCreditCost() != null) {
                segment.put("actualChargedCredits", task.actualCreditCost());
            }
            if (task.resultAssetId() != null) {
                segment.put("outputAssetId", task.resultAssetId());
            }
            if (StringUtils.hasText(task.errorCode())) {
                segment.put("errorCode", task.errorCode());
            }
            if (StringUtils.hasText(task.errorMessage())) {
                segment.put("errorMessage", task.errorMessage());
            }
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("taskType", task.taskType());
            metadata.put("modelCode", task.modelCode());
            metadata.put("provider", task.provider());
            metadata.put("progress", task.progress() == null ? 0 : task.progress());
            metadata.put("settlementStatus", task.settlementStatus());
            segment.set("providerMetadata", metadata);
            if ("succeeded".equals(status)) {
                String resultUrl = firstResultUrl(task.outputJson());
                if (StringUtils.hasText(resultUrl)) {
                    segment.put("resultUrl", resultUrl);
                }
                segment.put("completedAt", task.finishedAt() == null ? LocalDateTime.now().toString() : task.finishedAt().toString());
            }
        }
        return segment;
    }

    public ObjectNode draftForSubmit(ObjectNode segment) {
        JsonNode providerPayload = segment.path("providerPayload");
        JsonNode diagnostics = providerPayload.path("diagnosticMetadata");
        ObjectNode draft = diagnostics.isObject()
                ? (ObjectNode) diagnostics.deepCopy()
                : objectMapper.createObjectNode();
        String fullPrompt = text(segment, "segmentPrompt", text(providerPayload, "prompt", text(draft, "prompt")));
        if (StringUtils.hasText(fullPrompt)) {
            draft.put("prompt", concisePrompt(segment, fullPrompt));
            draft.put("scriptText", conciseScript(segment));
            draft.put("longVideoFullPrompt", fullPrompt);
        }
        normalizeRoles(draft);
        if (segment.has("localDialogues")) {
            draft.set("dialogueLines", normalizedDialogueLines(segment));
        }
        if (segment.has("localScenes")) {
            draft.set("shots", normalizedShots(segment));
        }
        if (segment.has("subtitleConfig")) {
            draft.set("subtitleConfig", segment.get("subtitleConfig").deepCopy());
        }
        if (segment.has("voiceMapping")) {
            ObjectNode audio = draft.has("audioConfig") && draft.get("audioConfig").isObject()
                    ? (ObjectNode) draft.get("audioConfig").deepCopy()
                    : objectMapper.createObjectNode();
            audio.set("voiceProfiles", segment.get("voiceMapping").deepCopy());
            draft.set("audioConfig", audio);
        }
        draft.put("templateType", "PET_STORY_LONG_VIDEO_SEGMENT");
        draft.put("longVideoSegmentIndex", intValue(segment, "segmentIndex", 0));
        draft.put("longVideoGlobalStart", intValue(segment, "globalStart", 0));
        draft.put("longVideoGlobalEnd", intValue(segment, "globalEnd", 0));
        return draft;
    }

    private void normalizeRoles(ObjectNode draft) {
        JsonNode roles = draft.path("roles");
        if (!roles.isArray()) {
            return;
        }
        for (JsonNode roleNode : roles) {
            if (!(roleNode instanceof ObjectNode role)) {
                continue;
            }
            if (!role.path("personalityTags").isArray() || role.path("personalityTags").isEmpty()) {
                ArrayNode tags = objectMapper.createArrayNode();
                String type = text(role, "type");
                if ("dog".equals(type)) {
                    tags.add("活泼");
                } else if ("cat".equals(type)) {
                    tags.add("淡定");
                } else {
                    tags.add("温和");
                }
                role.set("personalityTags", tags);
            }
            if (!StringUtils.hasText(text(role, "speakingTone"))) {
                role.put("speakingTone", "自然温和");
            }
        }
    }

    private ArrayNode normalizedDialogueLines(JsonNode segment) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode lines = segment.path("localDialogues");
        if (!lines.isArray()) {
            return result;
        }
        int index = 1;
        for (JsonNode line : lines) {
            String text = shortText(text(line, "text", text(line, "subtitle")), 80);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            ObjectNode row = objectMapper.createObjectNode();
            row.put("id", text(line, "id", "line-" + index));
            row.put("speakerRoleId", text(line, "speakerRoleId", text(line, "speakerId")));
            row.put("speakerId", text(line, "speakerId", text(line, "speakerRoleId")));
            row.put("text", text);
            row.put("emotion", text(line, "emotion", "自然"));
            row.put("speed", text(line, "speed", "normal"));
            row.put("voiceName", text(line, "voiceName", text(line, "voiceProfileId")));
            row.put("lipSync", line.path("lipSync").asBoolean(false));
            result.add(row);
            index++;
        }
        return result;
    }

    private ArrayNode normalizedShots(JsonNode segment) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode scenes = segment.path("localScenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            return result;
        }
        int targetDuration = Math.max(4, intValue(segment, "durationSeconds", 10));
        int shotCount = Math.max(3, scenes.size());
        int remaining = targetDuration;
        for (int i = 0; i < shotCount; i++) {
            JsonNode scene = scenes.get(Math.min(scenes.size() - 1, i * scenes.size() / shotCount));
            int duration = i == shotCount - 1 ? remaining : Math.max(1, Math.round((float) targetDuration / shotCount));
            remaining -= duration;
            ObjectNode shot = objectMapper.createObjectNode();
            shot.put("id", text(scene, "sceneId", "shot-" + (i + 1)) + "-" + (i + 1));
            shot.put("index", i + 1);
            shot.put("durationSeconds", duration);
            shot.put("frameDescription", text(scene, "frameDescription", text(scene, "visual", "温暖客厅里的宠物剧情画面")));
            shot.put("characterAction", text(scene, "characterAction", text(scene, "action", "宠物自然看向镜头")));
            shot.put("cameraMove", text(scene, "cameraMove", text(scene, "camera", "固定镜头")));
            shot.put("subtitle", shortText(subtitleForShot(segment, i), 36));
            result.add(shot);
        }
        return result;
    }

    private String subtitleForShot(JsonNode segment, int index) {
        JsonNode subtitles = segment.path("localSubtitles");
        if (subtitles.isArray() && !subtitles.isEmpty()) {
            JsonNode subtitle = subtitles.get(Math.min(index, subtitles.size() - 1));
            return text(subtitle, "text", text(subtitle, "subtitle"));
        }
        JsonNode lines = segment.path("localDialogues");
        if (lines.isArray() && !lines.isEmpty()) {
            JsonNode line = lines.get(Math.min(index, lines.size() - 1));
            return text(line, "subtitle", text(line, "text"));
        }
        return " ";
    }

    private String concisePrompt(JsonNode segment, String fullPrompt) {
        StringBuilder builder = new StringBuilder(520);
        builder.append("宠物剧情长视频第")
                .append(intValue(segment, "segmentIndex", 0))
                .append("段，")
                .append(intValue(segment, "durationSeconds", 0))
                .append("秒。保持上传宠物、主人和场景参考一致。");
        String scenes = sceneSummary(segment);
        if (StringUtils.hasText(scenes)) {
            builder.append("画面：").append(scenes);
        }
        String dialogues = dialogueSummary(segment);
        if (StringUtils.hasText(dialogues)) {
            builder.append("台词：").append(dialogues);
        }
        builder.append("固定或轻微推镜，真实温暖家庭感，不要汽车、品牌、水印、畸形或多余角色。");
        String value = builder.toString();
        if (!StringUtils.hasText(value)) {
            value = fullPrompt;
        }
        return value.length() <= 480 ? value : value.substring(0, 480);
    }

    private String conciseScript(JsonNode segment) {
        String dialogues = dialogueSummary(segment);
        if (StringUtils.hasText(dialogues)) {
            return dialogues.length() <= 480 ? dialogues : dialogues.substring(0, 480);
        }
        String scenes = sceneSummary(segment);
        return scenes.length() <= 480 ? scenes : scenes.substring(0, 480);
    }

    private String sceneSummary(JsonNode segment) {
        StringBuilder builder = new StringBuilder();
        JsonNode scenes = segment.path("localScenes");
        if (scenes.isArray()) {
            for (JsonNode scene : scenes) {
                appendCompact(builder, text(scene, "visual"));
                appendCompact(builder, text(scene, "action"));
                appendCompact(builder, text(scene, "camera"));
            }
        }
        return builder.toString();
    }

    private String dialogueSummary(JsonNode segment) {
        StringBuilder builder = new StringBuilder();
        JsonNode lines = segment.path("localDialogues");
        if (lines.isArray()) {
            for (JsonNode line : lines) {
                String speaker = text(line, "speakerId");
                String lineText = text(line, "text", text(line, "subtitle"));
                if (StringUtils.hasText(lineText)) {
                    if (builder.length() > 0) {
                        builder.append(" ");
                    }
                    if (StringUtils.hasText(speaker)) {
                        builder.append(speaker).append(": ");
                    }
                    builder.append(lineText);
                }
            }
        }
        return builder.toString();
    }

    private void appendCompact(StringBuilder builder, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" ");
        }
        builder.append(value.trim());
    }

    private String shortText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public boolean isTerminalFailure(String status) {
        return "failed".equals(status) || "cancelled".equals(status) || "timeout".equals(status);
    }

    private boolean hasTaskId(JsonNode segment) {
        return taskId(segment) != null || StringUtils.hasText(text(segment, "providerTaskId"));
    }

    private Long taskId(JsonNode segment) {
        Long generationTaskId = longValueOrNull(segment, "generationTaskId");
        if (generationTaskId != null) {
            return generationTaskId;
        }
        String providerTaskId = text(segment, "providerTaskId");
        return parseTaskId(providerTaskId);
    }

    private Long parseTaskId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String digits = value.trim().replaceFirst("^pet-video-", "").replaceFirst("^task-", "");
        if (!digits.matches("\\d+")) {
            return null;
        }
        return Long.parseLong(digits);
    }

    private String longVideoStatus(String taskStatus) {
        if (TaskStatusCode.SUCCESS.equals(taskStatus)) {
            return "succeeded";
        }
        if (TaskStatusCode.RUNNING.equals(taskStatus)) {
            return "processing";
        }
        if (TaskStatusCode.QUEUED.equals(taskStatus)) {
            return "submitted";
        }
        if (TaskStatusCode.CANCELED.equals(taskStatus)) {
            return "cancelled";
        }
        if (TaskStatusCode.FAILED.equals(taskStatus) || TaskStatusCode.RETRYABLE.equals(taskStatus)) {
            return "failed";
        }
        return "pending";
    }

    private String firstResultUrl(String outputJson) {
        if (!StringUtils.hasText(outputJson)) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(outputJson);
            for (String field : new String[]{"videoUrl", "url", "resultUrl", "fileUrl", "previewUrl"}) {
                JsonNode found = node.findValue(field);
                if (found != null && found.isTextual() && StringUtils.hasText(found.asText())) {
                    return found.asText().trim();
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private OptionalLong viewer(Long ownerUserId) {
        return ownerUserId == null ? OptionalLong.empty() : OptionalLong.of(ownerUserId);
    }

    private static Long longValueOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).canConvertToLong()) {
            return null;
        }
        return node.get(field).asLong();
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        Long value = longValueOrNull(node, field);
        return value == null ? fallback : value;
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : fallback;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
