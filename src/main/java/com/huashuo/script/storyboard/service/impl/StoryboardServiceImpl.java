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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Service
/**
 * 分镜服务实现：校验脚本版本归属，生成 mock 分镜 JSON，并通过 TaskService 记录任务全过程。
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

        List<StoryboardShotDto> shots = mockStoryboard(script.content());
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
                toJson(Map.of("promptTokens", promptTokens, "completionTokens", completionTokens, "mock", true))
        ));
        taskService.completeTask(task.taskId(), toJson(output));

        TaskItem done = taskService.getTask(task.taskId());
        return new StoryboardGenerateResponse(done.taskId(), done.status(), shots);
    }

    private List<StoryboardShotDto> mockStoryboard(String scriptContent) {
        String text = scriptContent == null ? "" : scriptContent.trim();
        String preview = text.length() > 80 ? text.substring(0, 80) + "…" : text;
        return List.of(
                new StoryboardShotDto(1, "全景 · 主讲人出镜", preview, 5.0),
                new StoryboardShotDto(2, "中景 · 产品/要点展示", "根据文案自动拆分的第二镜（mock）", 6.5),
                new StoryboardShotDto(3, "特写 · 行动号召", "关注与转化引导（mock）", 4.0)
        );
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
