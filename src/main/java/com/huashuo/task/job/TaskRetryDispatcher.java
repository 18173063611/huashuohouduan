package com.huashuo.task.job;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.mq.AiTaskPublisher;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
/**
 * 任务重试分发器：根据 taskType 触发对应的执行器。
 *
 * 注意：该类不依赖 TaskService，避免与 Executor（依赖 TaskService）形成循环依赖。
 */
public class TaskRetryDispatcher {

    private final AiTaskPublisher aiTaskPublisher;

    public TaskRetryDispatcher(AiTaskPublisher aiTaskPublisher) {
        this.aiTaskPublisher = aiTaskPublisher;
    }

    public void dispatch(TaskItem task) {
        if (task == null || task.taskId() == null) {
            throw new BusinessException(40000, "Task is missing, cannot retry");
        }
        if (!StringUtils.hasText(task.taskType())) {
            throw new BusinessException(40000, "Task type is missing, cannot retry");
        }
        String type = task.taskType().trim();
        if (TaskTypeCode.TTS_GENERATE.equals(type)
                || TaskTypeCode.AVATAR_GENERATE.equals(type)
                || TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(type)
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(type)
                || TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(type)
                || TaskTypeCode.DOUYIN_REWRITE.equals(type)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(type)
                || TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(type)
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(type)
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(type)
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(type)
                || TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(type)
                || TaskTypeCode.QUICK_RENDER.equals(type)
                || TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(type)
                || TaskTypeCode.VOICE_SAMPLE.equals(type)) {
            aiTaskPublisher.publishAfterCommit(task);
            return;
        }
        throw new BusinessException(40000, "This task type does not support retry yet: " + type);
    }
}
