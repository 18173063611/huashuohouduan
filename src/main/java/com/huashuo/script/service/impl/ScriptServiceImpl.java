package com.huashuo.script.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScriptServiceImpl implements ScriptService {

    private static final String SOURCE_MOCK_REWRITE = "MOCK_REWRITE";

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final ObjectMapper objectMapper;

    public ScriptServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ScriptVersionItem> listProjectScripts(Long projectId) {
        return scriptVersionService.listByProject(projectId);
    }

    @Override
    @Transactional
    public RewriteScriptResponse rewrite(RewriteScriptRequest request, String traceId) {
        String inputJson = toJson(Map.of(
                "projectId", request.projectId(),
                "sourceText", request.sourceText(),
                "style", request.style(),
                "targetLength", request.targetLength()
        ));
        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.SCRIPT_REWRITE,
                inputJson,
                traceId
        );
        taskService.startTask(task.taskId());

        String rewritten = mockRewrite(request.sourceText(), request.style(), request.targetLength());
        ScriptVersionItem version = scriptVersionService.createVersion(
                request.projectId(),
                rewritten,
                SOURCE_MOCK_REWRITE
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scriptVersionId", version.scriptVersionId());
        output.put("versionNo", version.versionNo());
        output.put("rewrittenText", rewritten);
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
}
