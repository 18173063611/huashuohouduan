package com.huashuo.writer.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
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
    private final AssetService assetService;

    public VideoScriptTaskExecutor(TaskService taskService, VideoScriptService videoScriptService,
                                   ObjectMapper objectMapper, AssetService assetService) {
        this.taskService = taskService;
        this.videoScriptService = videoScriptService;
        this.objectMapper = objectMapper;
        this.assetService = assetService;
    }

    public void run(Long taskId) {
        TaskItem task;
        try {
            task = taskService.getTask(taskId);
        } catch (Exception e) {
            log.warn("Video script task {} not found: {}", taskId, e.getMessage());
            return;
        }
        if (task.ownerUserId() == null) {
            log.warn("Video script task {} has null ownerUserId; refusing execution.", taskId);
            throw new BusinessException(40100, "分镜任务缺少用户信息，请重新登录后重试");
        }
        if (!TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(task.taskType())
                && !TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(task.taskType())) {
            throw new BusinessException(40000, "Unsupported video script task type: " + task.taskType());
        }

        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("Video script task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        try {
            task = taskService.getTask(taskId);
            if (isCanceled(taskId)) {
                log.info("Video script task {} stopped because it was canceled before execution.", taskId);
                return;
            }
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            String url = input.path("url").asText("");
            String platform = input.path("platform").asText(null);

            List<ScriptVO> scripts = videoScriptService.executeScriptAnalyzeForParentTask(url, task.taskType(), platform);
            if (isCanceled(taskId)) {
                log.info("Video script task {} stopped after analysis because it was canceled.", taskId);
                return;
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("scripts", scripts);
            AssetItem asset = createScriptAsset(task, url, scripts, output);
            output.put("resultAssetId", asset.assetId());
            output.put("previewUrl", asset.fileUrl());
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            if (isCanceled(taskId)) {
                log.info("Video script task {} ignored business exception after cancellation: {}", taskId, ex.getMessage());
                return;
            }
            log.warn("Video script task {} failed: {}", taskId, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            if (isCanceled(taskId)) {
                log.info("Video script task {} ignored runtime exception after cancellation: {}", taskId, ex.getMessage());
                return;
            }
            log.error("Video script task {} error", taskId, ex);
            throw ex;
        } catch (Exception ex) {
            if (isCanceled(taskId)) {
                log.info("Video script task {} ignored exception after cancellation: {}", taskId, ex.getMessage());
                return;
            }
            log.error("Video script task {} error", taskId, ex);
            throw new RetryableException(ex.getMessage() == null ? "Video script task failed" : ex.getMessage(), ex);
        }
    }

    private boolean isCanceled(Long taskId) {
        if (taskId == null) {
            return false;
        }
        try {
            return TaskStatusCode.CANCELED.equals(taskService.getTask(taskId).status());
        } catch (RuntimeException exception) {
            log.warn("Video script task {} cancel-state check failed: {}", taskId, exception.getMessage());
            return false;
        }
    }

    private AssetItem createScriptAsset(TaskItem task, String url, List<ScriptVO> scripts,
                                        Map<String, Object> output) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskType", task.taskType());
        meta.put("sourceUrl", url);
        meta.put("scriptCount", scripts == null ? 0 : scripts.size());
        meta.put("assetRole", "storyboard_json");
        return assetService.createGeneratedJsonAsset(
                task.ownerUserId(),
                task.projectId(),
                task.taskId(),
                "video-script-task-" + task.taskId() + ".json",
                objectMapper.writeValueAsString(output),
                "storyboard",
                task.taskType(),
                objectMapper.writeValueAsString(meta)
        );
    }
}
