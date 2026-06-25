package com.huashuo.script.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.script.dto.RewriteScriptRequest;
import com.huashuo.script.dto.RewriteScriptResponse;
import com.huashuo.script.service.ScriptService;
import com.huashuo.script.service.ScriptVersionService;
import com.huashuo.script.vo.ScriptVersionItem;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Service
/**
 * 文案服务实现：负责生成 mock 改写文案、保存脚本版本，并把任务状态交给 TaskService 维护。
 */
public class ScriptServiceImpl implements ScriptService {

    private static final String SOURCE_MOCK_REWRITE = "MOCK_REWRITE";

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;

    public ScriptServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
    }

    @Override
    public List<ScriptVersionItem> listProjectScripts(Long projectId, OptionalLong viewerUserId) {
        return scriptVersionService.listByProject(projectId, viewerUserId);
    }

    @Override
    @Transactional
    public RewriteScriptResponse rewrite(RewriteScriptRequest request, String traceId, Long ownerUserId) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (request.projectId() != null) {
            input.put("projectId", request.projectId());
        }
        input.put("sourceText", request.sourceText());
        input.put("style", request.style());
        input.put("targetLength", request.targetLength());
        String inputJson = toJson(input);
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.SCRIPT_REWRITE,
                inputJson,
                traceId,
                ownerUserId
        );
        taskService.startTask(task.taskId());

        String rewritten = mockRewrite(request.sourceText(), request.style(), request.targetLength());
        ScriptVersionItem version = scriptVersionService.createVersion(
                request.projectId(),
                rewritten,
                SOURCE_MOCK_REWRITE,
                ownerUserId
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scriptVersionId", version.scriptVersionId());
        output.put("versionNo", version.versionNo());
        output.put("rewrittenText", rewritten);
        int promptTokens = estimateTokens(request.sourceText());
        int completionTokens = estimateTokens(rewritten);
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
                toJson(Map.of("promptTokens", promptTokens, "completionTokens", completionTokens, "mock", true))
        ));
        taskService.completeTask(task.taskId(), toJson(output));

        TaskItem done = taskService.getTask(task.taskId());
        return new RewriteScriptResponse(
                done.taskId(),
                done.status(),
                version.scriptVersionId(),
                version.versionNo(),
                rewritten
        );
    }

    private String mockRewrite(String sourceText, String style, int targetLength) {
        String base = sourceText == null ? "" : sourceText.trim();
        if (isPunctuationOnlyStyle(style)) {
            return fitLength(addSpeechPunctuation(base), Math.max(targetLength, base.length() + 80));
        }
        String carInfo = extractLineValue(base, "车型资料：");
        if (hasText(carInfo)) {
            String userNeed = extractLineValue(base, "用户补充需求：");
            String selectedPoints = extractLineValue(base, "已选卖点：");
            String vehicle = firstNonBlank(segmentValue(carInfo, "车型"), firstSegment(carInfo), "这款车");
            String color = segmentValue(carInfo, "颜色");
            String points = firstNonBlank(segmentValue(carInfo, "卖点"), selectedPoints, "外观质感、实用配置和到店转化");
            String images = segmentValue(carInfo, "图片");
            boolean hasUserNeed = hasText(userNeed) && !userNeed.contains("未填写");
            StringBuilder script = new StringBuilder();
            script.append("今天带大家看").append(vehicle);
            if (hasText(color)) {
                script.append("，").append(color);
            }
            script.append("。\n");
            if (hasUserNeed) {
                script.append("本次重点围绕").append(userNeed).append("展开。\n");
            }
            script.append("第一眼先看整车姿态和细节质感，镜头要干净直接，让用户马上知道这是一台值得进店看的车。\n");
            script.append("中段重点讲").append(points).append("，用真实车辆画面对应卖点，不夸大、不串车。\n");
            if (hasText(images)) {
                script.append("素材里可以优先使用").append(images).append("，让外观、内饰和细节自然衔接。\n");
            }
            script.append("最后提醒用户预约试驾或到店咨询，把兴趣转成明确行动。");
            return fitLength(script.toString(), targetLength);
        }
        if (base.length() > targetLength) {
            base = base.substring(0, Math.min(base.length(), targetLength));
        } else if (base.length() < targetLength) {
            String pad = "（" + style + "风格扩写占位，目标约 " + targetLength + " 字）";
            base = base + pad;
            if (base.length() > targetLength) {
                base = base.substring(0, targetLength);
            }
        }
        return base;
    }

    private boolean isPunctuationOnlyStyle(String style) {
        if (!hasText(style)) {
            return false;
        }
        String normalized = style.toLowerCase();
        return normalized.contains("punctuation-only")
                || normalized.contains("punctuation")
                || normalized.contains("标点")
                || normalized.contains("断句");
    }

    private String addSpeechPunctuation(String text) {
        String clean = text == null ? "" : text.trim().replaceAll("[ \\t]+", " ");
        if (!hasText(clean)) {
            return "";
        }
        if (clean.matches(".*[，。！？；：,.!?;:].*")) {
            return clean;
        }
        if (clean.matches(".*[\\u4E00-\\u9FFF].*")) {
            return addChineseSpeechPunctuation(clean.replaceAll("\\s+", ""));
        }
        return addEnglishSpeechPunctuation(clean);
    }

    private String addChineseSpeechPunctuation(String text) {
        StringBuilder result = new StringBuilder();
        int clauseLength = 16;
        int sentenceLength = 34;
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(i));
            int pos = i + 1;
            if (pos < text.length() && pos % sentenceLength == 0) {
                result.append("。\n");
            } else if (pos < text.length() && pos % clauseLength == 0) {
                result.append("，");
            }
        }
        if (result.length() > 0 && "，。！？；：,.!?;:\n".indexOf(result.charAt(result.length() - 1)) < 0) {
            result.append("。");
        }
        return result.toString().trim();
    }

    private String addEnglishSpeechPunctuation(String text) {
        String[] words = text.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(words[i]);
            int pos = i + 1;
            if (pos < words.length && pos % 24 == 0) {
                result.append(".\n");
            } else if (pos < words.length && pos % 12 == 0) {
                result.append(",");
            }
        }
        if (result.length() > 0 && ".,!?;:\n".indexOf(result.charAt(result.length() - 1)) < 0) {
            result.append('.');
        }
        return result.toString().trim();
    }

    private String fitLength(String text, int targetLength) {
        String value = text == null ? "" : text.trim();
        if (targetLength > 0 && value.length() > targetLength) {
            return value.substring(0, targetLength).trim();
        }
        return value;
    }

    private String extractLineValue(String text, String prefix) {
        if (!hasText(text) || !hasText(prefix)) {
            return "";
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String segmentValue(String text, String label) {
        if (!hasText(text) || !hasText(label)) {
            return "";
        }
        for (String part : text.split("[；;]")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(label)) {
                return trimmed.substring(label.length()).trim();
            }
        }
        return "";
    }

    private String firstSegment(String text) {
        if (!hasText(text)) {
            return "";
        }
        String[] parts = text.split("[；;]");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
