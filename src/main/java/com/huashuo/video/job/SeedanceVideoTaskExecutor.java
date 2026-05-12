package com.huashuo.video.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.exception.RetryableException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
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
        } catch (Exception e) {
            log.warn("Seedance video task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        try {
            var task = taskService.getTask(taskId);
            String taskType = task.taskType();

            VideoTaskVO output;
            if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)) {
                output = videoService.generateText(objectMapper.readValue(task.inputJson(), TextDTO.class));
            } else if (TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)) {
                output = videoService.generateFirstFrame(objectMapper.readValue(task.inputJson(), ImageDTO.class));
            } else if (TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)) {
                output = videoService.generateFirstLastFrame(objectMapper.readValue(task.inputJson(), ImageFirstLastFrameDTO.class));
            } else if (TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)) {
                output = videoService.generateReference(objectMapper.readValue(task.inputJson(), ImageReferenceDTO.class));
            } else {
                throw new BusinessException(40000, "Unsupported Seedance video task type: " + taskType);
            }

            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
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
