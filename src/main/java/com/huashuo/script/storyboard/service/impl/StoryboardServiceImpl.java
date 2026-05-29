package com.huashuo.script.storyboard.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.script.service.ScriptVersionService;
import com.huashuo.script.vo.ScriptVersionItem;
import com.huashuo.script.storyboard.dto.StoryboardGenerateRequest;
import com.huashuo.script.storyboard.dto.StoryboardGenerateResponse;
import com.huashuo.script.storyboard.dto.StoryboardShotDto;
import com.huashuo.script.storyboard.service.StoryboardService;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Service
/**
 * 分镜服务实现：校验脚本版本归属，生成结构化分镜 JSON，并通过 TaskService 记录任务全过程。
 */
public class StoryboardServiceImpl implements StoryboardService {

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;
    private final AssetService assetService;

    public StoryboardServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService,
            AssetService assetService
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
        this.assetService = assetService;
    }

    @Override
    @Transactional
    public StoryboardGenerateResponse generate(StoryboardGenerateRequest request, String traceId, Long ownerUserId) {
        if (request.scriptVersionId() == null) {
            throw new BusinessException(40000, "Script version id is required");
        }
        OptionalLong viewer = ownerUserId == null ? OptionalLong.empty() : OptionalLong.of(ownerUserId);
        ScriptVersionItem script = scriptVersionService.requireForProject(
                request.projectId(),
                request.scriptVersionId(),
                viewer
        );
        Map<String, Object> input = new LinkedHashMap<>();
        if (request.projectId() != null) {
            input.put("projectId", request.projectId());
        }
        input.put("scriptVersionId", request.scriptVersionId());
        String inputJson = toJson(input);
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.STORYBOARD_GENERATE,
                inputJson,
                traceId,
                ownerUserId
        );
        taskService.startTask(task.taskId());

        List<StoryboardShotDto> shots = generateStoryboardFromScript(script.content());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("storyboard", shots);
        if (ownerUserId != null) {
            Map<String, Object> assetMeta = new LinkedHashMap<>();
            assetMeta.put("taskType", TaskTypeCode.STORYBOARD_GENERATE);
            assetMeta.put("scriptVersionId", request.scriptVersionId());
            assetMeta.put("projectId", request.projectId());
            assetMeta.put("shotCount", shots.size());
            AssetItem asset = assetService.createGeneratedJsonAsset(
                    ownerUserId,
                    request.projectId(),
                    task.taskId(),
                    "storyboard-task-" + task.taskId() + ".json",
                    toJson(output),
                    "storyboard",
                    TaskTypeCode.STORYBOARD_GENERATE,
                    toJson(assetMeta)
            );
            output.put("resultAssetId", asset.assetId());
            output.put("previewUrl", asset.fileUrl());
        }
        int promptTokens = estimateTokens(script.content());
        int completionTokens = estimateTokens(toJson(output));
        creditBillingService.settle(task.taskId(), new UsageActualResult(
                task.provider(),
                task.modelCode(),
                UsageUnit.TOKEN,
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                toJson(Map.of("promptTokens", promptTokens, "completionTokens", completionTokens, "heuristicStoryboard", true))
        ));
        taskService.completeTask(task.taskId(), toJson(output));

        TaskItem done = taskService.getTask(task.taskId());
        return new StoryboardGenerateResponse(done.taskId(), done.status(), shots);
    }

    private List<StoryboardShotDto> generateStoryboardFromScript(String scriptContent) {
        String text = scriptContent == null ? "" : scriptContent.trim();
        int shotCount = text.length() > 420 ? 6 : (text.length() > 220 ? 5 : 4);
        List<String> narrationChunks = splitNarration(text, shotCount);
        String[] visualPlans = {
                "开场建立 · 全景到中景 · 镜头慢速推进 · 先建立主体和场景氛围，画面干净留出短视频裁切安全区",
                "卖点承接 · 中景/中近景 · 平稳横移或轻推 · 围绕当前核心信息安排一个明确展示目标",
                "细节强化 · 近景/特写 · 锁定或微距慢推 · 突出一个可见细节、材质、动作或配置点",
                "场景代入 · 中远景/跟拍 · 顺主体运动方向移动 · 让画面与真实使用场景发生关系",
                "信任补充 · 中景 · 稳定镜头轻微推进 · 展示体验、权益、服务或对比信息",
                "转化收口 · 中景到近景 · 结尾停在稳定画面 · 承接咨询、预约、关注或行动号召"
        };
        double[] durations = {5.0, 6.0, 5.5, 6.0, 5.5, 4.5};
        List<StoryboardShotDto> shots = new ArrayList<>();
        for (int i = 0; i < shotCount; i++) {
            shots.add(new StoryboardShotDto(
                    i + 1,
                    visualPlans[Math.min(i, visualPlans.length - 1)],
                    narrationChunks.size() > i ? narrationChunks.get(i) : "按该段画面节奏承接原文内容",
                    durations[Math.min(i, durations.length - 1)]
            ));
        }
        return shots;
    }

    private List<String> splitNarration(String text, int count) {
        int safeCount = Math.max(1, count);
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            for (int i = 0; i < safeCount; i++) {
                chunks.add(i == safeCount - 1 ? "引导咨询或预约下一步" : "根据脚本安排该段口播");
            }
            return chunks;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        int targetLength = Math.max(30, (int) Math.ceil(compact.length() / (double) safeCount));
        int cursor = 0;
        for (int i = 0; i < safeCount; i++) {
            if (cursor >= compact.length()) {
                chunks.add("承接上一段信息，保持画面节奏自然");
                continue;
            }
            int end = i == safeCount - 1 ? compact.length() : Math.min(compact.length(), cursor + targetLength);
            int punctuation = nextPunctuationIndex(compact, cursor, end);
            if (punctuation > cursor) {
                end = punctuation + 1;
            }
            chunks.add(limitText(compact.substring(cursor, end).trim(), 120));
            cursor = end;
        }
        return chunks;
    }

    private int nextPunctuationIndex(String text, int start, int preferredEnd) {
        int searchEnd = Math.min(text.length(), preferredEnd + 24);
        for (int i = preferredEnd; i < searchEnd; i++) {
            char ch = text.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == ';' || ch == '；'
                    || ch == '!' || ch == '?') {
                return i;
            }
        }
        return -1;
    }

    private String limitText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize JSON");
        }
    }

    private int estimateTokens(String text) {
        String value = text == null ? "" : text.trim();
        return Math.max(1, BigDecimal.valueOf(value.length())
                .divide(BigDecimal.valueOf(1.5), 0, java.math.RoundingMode.CEILING)
                .intValue());
    }
}
