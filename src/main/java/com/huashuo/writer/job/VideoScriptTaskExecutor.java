package com.huashuo.writer.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.writer.vo.ScriptVO;
import com.huashuo.writer.service.VideoScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VideoScriptTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(VideoScriptTaskExecutor.class);

    private final TaskService taskService;
    private final VideoScriptService videoScriptService;
    private final ObjectMapper objectMapper;

    public VideoScriptTaskExecutor(TaskService taskService, VideoScriptService videoScriptService,
                                   ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.videoScriptService = videoScriptService;
        this.objectMapper = objectMapper;
    }

    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("Video script task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        try {
            var task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            String url = input.path("url").asText("");

            List<ScriptVO> scripts;
            if (TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(task.taskType())) {
                scripts = videoScriptService.scriptAnalyzeByUrl(url);
            } else {
                scripts = videoScriptService.scriptAnalyze(url);
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("scripts", scripts);
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("Video script task {} failed: {}", taskId, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Video script task {} error", taskId, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Video script task {} error", taskId, ex);
            throw new RetryableException(ex.getMessage() == null ? "Video script task failed" : ex.getMessage(), ex);
        }
    }
}
