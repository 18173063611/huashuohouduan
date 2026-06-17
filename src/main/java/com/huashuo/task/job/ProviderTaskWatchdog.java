package com.huashuo.task.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.mapper.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "huashuo.ai-task.provider-watchdog", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ProviderTaskWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ProviderTaskWatchdog.class);

    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final int maxItems;
    private final long warnAfterSeconds;
    private final double alertRatio;
    private final int alertRefreshMinutes;

    public ProviderTaskWatchdog(
            TaskMapper taskMapper,
            ObjectMapper objectMapper,
            @Value("${huashuo.ai-task.provider-watchdog.max-items:80}") int maxItems,
            @Value("${huashuo.ai-task.provider-watchdog.warn-after-seconds:900}") long warnAfterSeconds,
            @Value("${huashuo.ai-task.provider-watchdog.alert-ratio:0.75}") double alertRatio,
            @Value("${huashuo.ai-task.provider-watchdog.alert-refresh-minutes:10}") int alertRefreshMinutes
    ) {
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.maxItems = Math.max(1, maxItems);
        this.warnAfterSeconds = Math.max(60L, warnAfterSeconds);
        this.alertRatio = Math.max(0.1d, Math.min(1.0d, alertRatio));
        this.alertRefreshMinutes = Math.max(1, alertRefreshMinutes);
    }

    @Scheduled(
            initialDelayString = "${huashuo.ai-task.provider-watchdog.scan-initial-delay-ms:60000}",
            fixedDelayString = "${huashuo.ai-task.provider-watchdog.scan-interval-ms:300000}"
    )
    public void scanLongRunningProviderTasks() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startedBefore = now.minusSeconds(warnAfterSeconds);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .select(TaskEntity::getTaskId,
                        TaskEntity::getOwnerUserId,
                        TaskEntity::getTaskType,
                        TaskEntity::getStatus,
                        TaskEntity::getProgress,
                        TaskEntity::getOutputJson,
                        TaskEntity::getTraceId,
                        TaskEntity::getStartedAt,
                        TaskEntity::getCreatedAt,
                        TaskEntity::getUpdatedAt)
                .eq(TaskEntity::getStatus, TaskStatusCode.RUNNING)
                .isNotNull(TaskEntity::getOutputJson)
                .le(TaskEntity::getStartedAt, startedBefore)
                .orderByAsc(TaskEntity::getStartedAt)
                .last("limit " + maxItems);
        List<TaskEntity> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return;
        }
        int alerted = 0;
        for (TaskEntity task : tasks) {
            if (inspectTask(task, now)) {
                alerted++;
            }
        }
        if (alerted > 0) {
            log.warn("Provider task watchdog refreshed {} long-running alert(s).", alerted);
        }
    }

    private boolean inspectTask(TaskEntity task, LocalDateTime now) {
        Map<String, Object> output = readOutputJson(task);
        ProviderRuntime runtime = providerRuntime(task, output, now);
        if (runtime == null || !runtime.longRunning()) {
            return false;
        }
        if (!shouldRefreshAlert(output, now)) {
            return false;
        }
        log.warn("Provider task long running taskId={} taskType={} providerTaskId={} providerStatus={} elapsedSeconds={} timeoutSeconds={} canDelete={} nextAction={}",
                task.getTaskId(), task.getTaskType(), runtime.providerTaskId(), runtime.providerStatus(),
                runtime.elapsedSeconds(), runtime.timeoutSeconds(), runtime.canDeleteProviderTask(),
                runtime.nextAction());
        persistAlert(task, output, runtime, now);
        return true;
    }

    private ProviderRuntime providerRuntime(TaskEntity task, Map<String, Object> output, LocalDateTime now) {
        String providerTaskId = stringValue(output.get("activeProviderTaskId"));
        if (!StringUtils.hasText(providerTaskId)) {
            return null;
        }
        String providerStatus = stringValue(output.get("activeProviderStatus"));
        if (!"queued".equalsIgnoreCase(providerStatus) && !"running".equalsIgnoreCase(providerStatus)) {
            return null;
        }
        long elapsedSeconds = longValue(output.get("activeSegmentElapsedSeconds"));
        if (elapsedSeconds <= 0 && task.getStartedAt() != null) {
            elapsedSeconds = Math.max(0L, Duration.between(task.getStartedAt(), now).getSeconds());
        }
        long timeoutSeconds = longValue(output.get("activeSegmentTimeoutSeconds"));
        boolean exceedsRatio = timeoutSeconds > 0 && elapsedSeconds >= Math.round(timeoutSeconds * alertRatio);
        boolean longRunning = elapsedSeconds >= warnAfterSeconds || exceedsRatio;
        boolean canDelete = "queued".equalsIgnoreCase(providerStatus);
        String nextAction = canDelete ? "provider_delete_on_timeout" : "manual_provider_escalation";
        return new ProviderRuntime(
                providerTaskId,
                providerStatus,
                elapsedSeconds,
                timeoutSeconds,
                longValue(output.get("activeProviderUpdatedAt")),
                canDelete,
                nextAction,
                longRunning
        );
    }

    private boolean shouldRefreshAlert(Map<String, Object> output, LocalDateTime now) {
        Object raw = output.get("providerOpsAlert");
        if (!(raw instanceof Map<?, ?> map)) {
            return true;
        }
        String alertedAt = stringValue(map.get("alertedAt"));
        if (!StringUtils.hasText(alertedAt)) {
            return true;
        }
        try {
            LocalDateTime previous = LocalDateTime.parse(alertedAt);
            return previous.plusMinutes(alertRefreshMinutes).isBefore(now);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void persistAlert(TaskEntity task, Map<String, Object> output, ProviderRuntime runtime, LocalDateTime now) {
        try {
            Map<String, Object> next = new LinkedHashMap<>(output);
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("level", "warning");
            alert.put("reason", "provider_long_running");
            alert.put("providerTaskId", runtime.providerTaskId());
            alert.put("providerStatus", runtime.providerStatus());
            alert.put("elapsedSeconds", runtime.elapsedSeconds());
            alert.put("timeoutSeconds", runtime.timeoutSeconds());
            alert.put("providerUpdatedAt", runtime.providerUpdatedAt());
            alert.put("canDeleteProviderTask", runtime.canDeleteProviderTask());
            alert.put("nextAction", runtime.nextAction());
            alert.put("alertedAt", now.toString());
            alert.put("message", runtime.canDeleteProviderTask()
                    ? "Provider task is still queued; timeout compensation may delete it before retry."
                    : "Provider task is running and cannot be deleted by the current platform API; escalate manually if it exceeds the business window.");
            next.put("providerOpsAlert", alert);
            String outputJson = objectMapper.writeValueAsString(next);
            LambdaUpdateWrapper<TaskEntity> update = new LambdaUpdateWrapper<TaskEntity>()
                    .eq(TaskEntity::getTaskId, task.getTaskId())
                    .eq(TaskEntity::getStatus, TaskStatusCode.RUNNING)
                    .set(TaskEntity::getOutputJson, outputJson)
                    .set(TaskEntity::getUpdatedAt, now);
            taskMapper.update(null, update);
        } catch (Exception e) {
            log.warn("Provider task watchdog failed to persist alert taskId={} reason={}",
                    task.getTaskId(), e.getMessage());
        }
    }

    private Map<String, Object> readOutputJson(TaskEntity task) {
        String outputJson = task == null ? null : task.getOutputJson();
        if (!StringUtils.hasText(outputJson)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(outputJson, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private record ProviderRuntime(
            String providerTaskId,
            String providerStatus,
            long elapsedSeconds,
            long timeoutSeconds,
            long providerUpdatedAt,
            boolean canDeleteProviderTask,
            String nextAction,
            boolean longRunning
    ) {
    }
}
