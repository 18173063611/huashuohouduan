package com.huashuo.task.config;

import com.huashuo.task.enums.TaskTypeCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "huashuo.credit")
public class TaskCreditProperties {

    private Map<String, Long> taskCosts = defaultTaskCosts();

    public Map<String, Long> getTaskCosts() {
        return taskCosts;
    }

    public void setTaskCosts(Map<String, Long> taskCosts) {
        this.taskCosts = new LinkedHashMap<>(defaultTaskCosts());
        if (taskCosts != null) {
            taskCosts.forEach((key, value) -> {
                if (key != null && value != null) {
                    this.taskCosts.put(key.trim().toUpperCase(), Math.max(0L, value));
                }
            });
        }
    }

    public long costFor(String taskType) {
        if (taskType == null) {
            return 0L;
        }
        return Math.max(0L, taskCosts.getOrDefault(taskType.trim().toUpperCase(), 0L));
    }

    private static Map<String, Long> defaultTaskCosts() {
        Map<String, Long> defaults = new LinkedHashMap<>();
        defaults.put(TaskTypeCode.SCRIPT_REWRITE, 0L);
        defaults.put(TaskTypeCode.STORYBOARD_GENERATE, 0L);
        defaults.put(TaskTypeCode.VOICE_SAMPLE, 0L);
        defaults.put(TaskTypeCode.TTS_GENERATE, 1L);
        defaults.put(TaskTypeCode.AVATAR_GENERATE, 5L);
        defaults.put(TaskTypeCode.DIGITAL_HUMAN_GENERATE, 10L);
        defaults.put(TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT, 0L);
        return defaults;
    }
}
