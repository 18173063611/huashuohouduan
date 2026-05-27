package com.huashuo.video.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.service.TaskService;
import com.huashuo.video.DTO.QuickRenderResponse;
import com.huashuo.video.service.QuickRenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 一键成片任务执行器：消费 QUICK_RENDER 队列消息，完成素材识别和下游生成任务创建。
 */
@Component
public class QuickRenderTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(QuickRenderTaskExecutor.class);

    private final TaskService taskService;
    private final QuickRenderService quickRenderService;
    private final ObjectMapper objectMapper;

    public QuickRenderTaskExecutor(TaskService taskService,
                                   QuickRenderService quickRenderService,
                                   ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.quickRenderService = quickRenderService;
        this.objectMapper = objectMapper;
    }

    public void run(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(40000, "一键成片任务 ID 不能为空");
        }
        taskService.startTask(taskId);
        taskService.updateTaskProgress(taskId, 30);
        QuickRenderResponse response = quickRenderService.executeForExistingTask(taskId);
        taskService.updateTaskProgress(taskId, 90);
        taskService.completeTask(taskId, toJson(response));
        log.info("Quick render task {} submitted downstream route={}", taskId, response.getRoute());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "一键成片任务结果序列化失败");
        }
    }
}
