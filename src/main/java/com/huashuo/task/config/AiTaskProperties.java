package com.huashuo.task.config;

import com.huashuo.task.enums.TaskTypeCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "huashuo.ai-task")
public class AiTaskProperties {

    private Listener listener = new Listener();
    private Limits limits = new Limits();
    private Execution execution = new Execution();

    public Listener getListener() {
        return listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() : listener;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits == null ? new Limits() : limits;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution == null ? new Execution() : execution;
    }

    public static class Listener {
        private boolean enabled = true;
        private ListenerContainer regular = new ListenerContainer(3, 8, 1);
        private ListenerContainer tts = new ListenerContainer(3, 6, 1);
        private ListenerContainer writer = new ListenerContainer(2, 5, 1);
        private ListenerContainer video = new ListenerContainer(1, 3, 1);
        private ListenerContainer avatar = new ListenerContainer(1, 3, 1);
        private ListenerContainer douyinParseTranscript = new ListenerContainer(1, 2, 1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ListenerContainer getRegular() {
            return regular;
        }

        public void setRegular(ListenerContainer regular) {
            this.regular = regular == null ? new ListenerContainer(3, 8, 1) : regular;
        }

        public ListenerContainer getTts() {
            return tts;
        }

        public void setTts(ListenerContainer tts) {
            this.tts = tts == null ? new ListenerContainer(3, 6, 1) : tts;
        }

        public ListenerContainer getWriter() {
            return writer;
        }

        public void setWriter(ListenerContainer writer) {
            this.writer = writer == null ? new ListenerContainer(2, 5, 1) : writer;
        }

        public ListenerContainer getVideo() {
            return video;
        }

        public void setVideo(ListenerContainer video) {
            this.video = video == null ? new ListenerContainer(1, 3, 1) : video;
        }

        public ListenerContainer getAvatar() {
            return avatar;
        }

        public void setAvatar(ListenerContainer avatar) {
            this.avatar = avatar == null ? new ListenerContainer(1, 3, 1) : avatar;
        }

        public ListenerContainer getDouyinParseTranscript() {
            return douyinParseTranscript;
        }

        public void setDouyinParseTranscript(ListenerContainer douyinParseTranscript) {
            this.douyinParseTranscript = douyinParseTranscript == null
                    ? new ListenerContainer(1, 2, 1)
                    : douyinParseTranscript;
        }
    }

    public static class ListenerContainer {
        private int concurrentConsumers;
        private int maxConcurrentConsumers;
        private int prefetchCount;

        public ListenerContainer() {
            this(1, 1, 1);
        }

        public ListenerContainer(int concurrentConsumers, int maxConcurrentConsumers, int prefetchCount) {
            this.concurrentConsumers = concurrentConsumers;
            this.maxConcurrentConsumers = maxConcurrentConsumers;
            this.prefetchCount = prefetchCount;
        }

        public int getConcurrentConsumers() {
            return Math.max(1, concurrentConsumers);
        }

        public void setConcurrentConsumers(int concurrentConsumers) {
            this.concurrentConsumers = concurrentConsumers;
        }

        public int getMaxConcurrentConsumers() {
            return Math.max(getConcurrentConsumers(), maxConcurrentConsumers);
        }

        public void setMaxConcurrentConsumers(int maxConcurrentConsumers) {
            this.maxConcurrentConsumers = maxConcurrentConsumers;
        }

        public int getPrefetchCount() {
            return Math.max(1, prefetchCount);
        }

        public void setPrefetchCount(int prefetchCount) {
            this.prefetchCount = prefetchCount;
        }
    }

    public static class Limits {
        private boolean enabled = true;
        private int maxActiveGlobal = 500;
        private int maxActivePerUser = 10;
        private int maxActiveHeavyPerUser = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxActiveGlobal() {
            return Math.max(1, maxActiveGlobal);
        }

        public void setMaxActiveGlobal(int maxActiveGlobal) {
            this.maxActiveGlobal = maxActiveGlobal;
        }

        public int getMaxActivePerUser() {
            return Math.max(1, maxActivePerUser);
        }

        public void setMaxActivePerUser(int maxActivePerUser) {
            this.maxActivePerUser = maxActivePerUser;
        }

        public int getMaxActiveHeavyPerUser() {
            return Math.max(1, maxActiveHeavyPerUser);
        }

        public void setMaxActiveHeavyPerUser(int maxActiveHeavyPerUser) {
            this.maxActiveHeavyPerUser = maxActiveHeavyPerUser;
        }
    }

    public static class Execution {
        private int defaultMaxConcurrent = 4;
        private Map<String, Integer> maxConcurrentByTaskType = defaultTaskTypeConcurrency();

        public int getDefaultMaxConcurrent() {
            return Math.max(1, defaultMaxConcurrent);
        }

        public void setDefaultMaxConcurrent(int defaultMaxConcurrent) {
            this.defaultMaxConcurrent = defaultMaxConcurrent;
        }

        public Map<String, Integer> getMaxConcurrentByTaskType() {
            return maxConcurrentByTaskType;
        }

        public void setMaxConcurrentByTaskType(Map<String, Integer> maxConcurrentByTaskType) {
            this.maxConcurrentByTaskType = new LinkedHashMap<>(defaultTaskTypeConcurrency());
            if (maxConcurrentByTaskType != null) {
                maxConcurrentByTaskType.forEach((key, value) -> {
                    if (key != null && value != null) {
                        this.maxConcurrentByTaskType.put(key.trim().toUpperCase(), Math.max(1, value));
                    }
                });
            }
        }

        public int maxConcurrentFor(String taskType) {
            if (taskType == null) {
                return getDefaultMaxConcurrent();
            }
            return Math.max(1, maxConcurrentByTaskType.getOrDefault(
                    taskType.trim().toUpperCase(),
                    getDefaultMaxConcurrent()
            ));
        }

        private static Map<String, Integer> defaultTaskTypeConcurrency() {
            Map<String, Integer> defaults = new LinkedHashMap<>();
            defaults.put(TaskTypeCode.TTS_GENERATE, 4);
            defaults.put(TaskTypeCode.VOICE_SAMPLE, 4);
            defaults.put(TaskTypeCode.AVATAR_GENERATE, 2);
            defaults.put(TaskTypeCode.VIDEO_SCRIPT_ANALYZE, 3);
            defaults.put(TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE, 3);
            defaults.put(TaskTypeCode.DOUYIN_REWRITE, 3);
            defaults.put(TaskTypeCode.DOUYIN_TRANSCRIPT, 3);
            defaults.put(TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT, 2);
            defaults.put(TaskTypeCode.SEEDANCE_TEXT_VIDEO, 2);
            defaults.put(TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO, 2);
            defaults.put(TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO, 2);
            defaults.put(TaskTypeCode.SEEDANCE_REFERENCE_VIDEO, 2);
            defaults.put(TaskTypeCode.DIGITAL_HUMAN_GENERATE, 1);
            return defaults;
        }
    }
}
