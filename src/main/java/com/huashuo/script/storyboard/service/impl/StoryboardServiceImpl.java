package com.huashuo.script.storyboard.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * 分镜服务实现：校验脚本版本归属，生成 mock 分镜 JSON，并通过 TaskService 记录任务全过程。
 */
public class StoryboardServiceImpl implements StoryboardService {

    private final TaskService taskService;
    private final ScriptVersionService scriptVersionService;
    private final ObjectMapper objectMapper;

    public StoryboardServiceImpl(
            TaskService taskService,
            ScriptVersionService scriptVersionService,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.scriptVersionService = scriptVersionService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public StoryboardGenerateResponse generate(StoryboardGenerateRequest request, String traceId) {
        if (request.scriptVersionId() == null) {
            throw new BusinessException(40000, "Script version id is required");
        }
        ScriptVersionItem script = scriptVersionService.requireForProject(
                request.projectId(),
                request.scriptVersionId()
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
                traceId
        );
        taskService.startTask(task.taskId());

        List<StoryboardShotDto> shots = mockStoryboard(script.content());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("storyboard", shots);
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
}
