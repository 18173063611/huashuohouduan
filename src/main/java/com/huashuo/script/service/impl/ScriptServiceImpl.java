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
