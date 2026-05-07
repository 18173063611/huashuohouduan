package com.huashuo.task.job;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.avatar.job.AvatarGenerateTaskExecutor;
import com.huashuo.voice.job.TtsTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
/**
 * 任务重试分发器：根据 taskType 触发对应的执行器。
 *
 * 注意：该类不依赖 TaskService，避免与 Executor（依赖 TaskService）形成循环依赖。
 */
public class TaskRetryDispatcher {

    private final TtsTaskExecutor ttsTaskExecutor;
    private final AvatarGenerateTaskExecutor avatarGenerateTaskExecutor;

    public TaskRetryDispatcher(TtsTaskExecutor ttsTaskExecutor, AvatarGenerateTaskExecutor avatarGenerateTaskExecutor) {
        this.ttsTaskExecutor = ttsTaskExecutor;
        this.avatarGenerateTaskExecutor = avatarGenerateTaskExecutor;
    }

    public void dispatch(TaskItem task) {
        if (task == null || task.taskId() == null) {
            throw new BusinessException(40000, "Task is missing, cannot retry");
        }
        if (!StringUtils.hasText(task.taskType())) {
            throw new BusinessException(40000, "Task type is missing, cannot retry");
        }
        String type = task.taskType().trim();
        if (TaskTypeCode.TTS_GENERATE.equals(type)) {
            ttsTaskExecutor.run(task.taskId());
            return;
        }
        if (TaskTypeCode.AVATAR_GENERATE.equals(type)) {
            avatarGenerateTaskExecutor.run(task.taskId());
            return;
        }
        throw new BusinessException(40000, "This task type does not support retry yet: " + type);
    }
}

