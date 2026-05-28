package com.huashuo.video.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.service.TaskService;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.service.VideoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SeedanceVideoTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(SeedanceVideoTaskExecutor.class);

    private final TaskService taskService;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;

    public SeedanceVideoTaskExecutor(TaskService taskService, VideoService videoService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
    }

    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
            log.info("Seedance video task {} claimed and started", taskId);
        } catch (Exception e) {
            log.warn("Seedance video task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        try {
            VideoTaskVO output = videoService.executeForExistingTask(taskId);
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
            log.info("Seedance video task {} completed, remoteTaskId={}, resultAssetId={}",
                    taskId, output == null ? null : output.getTaskId(), output == null ? null : output.getResultAssetId());
        } catch (BusinessException ex) {
            log.warn("Seedance video task {} failed: {}", taskId, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Seedance video task {} error", taskId, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Seedance video task {} error", taskId, ex);
            throw new RetryableException(ex.getMessage() == null ? "Seedance video task failed" : ex.getMessage(), ex);
        }
    }
}
