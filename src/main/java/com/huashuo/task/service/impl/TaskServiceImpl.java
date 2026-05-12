package com.huashuo.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.config.TaskCreditProperties;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.task.vo.TaskSummaryResponse;
import com.huashuo.user.service.CreditChangeResult;
import com.huashuo.user.service.CreditService;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * 任务台账：创建占位、运行中/成功/失败更新、重试与列表查询。
 */
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, TaskEntity> implements TaskService {

    private static final int PAGESIZE_DEFAULT = 10;

    private final ObjectMapper objectMapper;
    private final VoiceProfileMapper voiceProfileMapper;
    private final CreditService creditService;
    private final TaskCreditProperties taskCreditProperties;

    @Override
    @Transactional
    public TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId, Long ownerUserId) {
        return createTask(projectId, taskType, inputJson, traceId, ownerUserId, null, null, null);
    }

    @Override
    @Transactional
    public TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId, Long ownerUserId,
                               String modelCode, Long creditCost, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        String normalizedModelCode = resolveModelCode(modelCode, inputJson);
        long resolvedCreditCost = resolveCreditCost(taskType, creditCost);
        String idempotency = trimToNull(idempotencyKey);
        if (idempotency != null) {
            TaskEntity existing = findTaskByIdempotencyKey(idempotency);
            if (existing != null) {
                assertIdempotencyKeyOwner(existing, ownerUserId);
                return toItem(existing);
            }
        }
        if (resolvedCreditCost > 0 && ownerUserId != null) {
            creditService.assertBalanceAtLeast(ownerUserId, resolvedCreditCost);
        }
        TaskEntity entity = new TaskEntity();
        entity.setProjectId(projectId);
        entity.setOwnerUserId(ownerUserId);
        entity.setTaskType(taskType);
        entity.setModelCode(normalizedModelCode);
        entity.setCreditCost(resolvedCreditCost);
        entity.setCreditLogId(null);
        entity.setQueueName(null);
        entity.setMessageId(null);
        entity.setIdempotencyKey(idempotency);
        entity.setPriority(0);
        entity.setStatus(TaskStatusCode.QUEUED);
        entity.setProgress(0);
        entity.setInputJson(inputJson);
        entity.setOutputJson(null);
        entity.setResultAssetId(null);
        entity.setErrorCode(null);
        entity.setRetryCount(0);
        entity.setErrorMessage(null);
        entity.setTraceId(traceId);
        entity.setResultViewed(0);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            save(entity);
        } catch (DataIntegrityViolationException ex) {
            if (idempotency != null) {
                TaskEntity raced = findTaskByIdempotencyKey(idempotency);
                if (raced != null) {
                    assertIdempotencyKeyOwner(raced, ownerUserId);
                    return toItem(raced);
                }
            }
            throw ex;
        }
        if (resolvedCreditCost > 0) {
            CreditChangeResult creditLog = creditService.consumeForTask(
                    ownerUserId,
                    entity.getTaskId(),
                    normalizedModelCode,
                    resolvedCreditCost,
                    consumeIdempotencyKey(entity),
                    "AI 任务提交预扣"
            );
            entity.setCreditLogId(creditLog.creditLogId());
            entity.setUpdatedAt(LocalDateTime.now());
            updateById(entity);
        }
        return toItem(entity);
    }

    @Override
    @Transactional
    public void startTask(long taskId) {
        TaskEntity entity = requireEntity(taskId);
        if (!TaskStatusCode.QUEUED.equals(entity.getStatus())
                && !TaskStatusCode.RETRYABLE.equals(entity.getStatus())) {
            throw new BusinessException(40900, "任务状态不允许开始执行");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(TaskStatusCode.RUNNING);
        entity.setProgress(entity.getProgress() != null && entity.getProgress() > 10 ? entity.getProgress() : 10);
        if (entity.getStartedAt() == null) {
            entity.setStartedAt(now);
        }
        entity.setFinishedAt(null);
        entity.setUpdatedAt(now);
        updateById(entity);
    }

    @Override
    @Transactional
    public void updateTaskProgress(long taskId, int progress) {
        TaskEntity entity = requireEntity(taskId);
        if (!TaskStatusCode.RUNNING.equals(entity.getStatus())) {
            return;
        }
        int clamped = Math.max(0, Math.min(100, progress));
        int current = entity.getProgress() == null ? 0 : entity.getProgress();
        if (clamped < current) {
            return;
        }
        entity.setProgress(clamped);
        entity.setUpdatedAt(LocalDateTime.now());
        updateById(entity);
    }

    @Override
    @Transactional
    public void completeTask(long taskId, String outputJson) {
        TaskEntity entity = requireEntity(taskId);
        if (!TaskStatusCode.RUNNING.equals(entity.getStatus())) {
            throw new BusinessException(40900, "任务状态不允许标记成功");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(TaskStatusCode.SUCCESS);
        entity.setProgress(100);
        entity.setOutputJson(outputJson);
        entity.setErrorMessage(null);
        entity.setErrorCode(null);
        entity.setResultAssetId(parseResultAssetId(entity.getTaskType(), outputJson));
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        updateById(entity);
    }

    @Override
    @Transactional
    public void failTask(long taskId, String errorMessage, boolean retryable, boolean refundCredits) {
        TaskEntity entity = requireEntity(taskId);
        if (!TaskStatusCode.RUNNING.equals(entity.getStatus())
                && !TaskStatusCode.QUEUED.equals(entity.getStatus())) {
            throw new BusinessException(40900, "任务状态不允许标记失败");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(retryable ? TaskStatusCode.RETRYABLE : TaskStatusCode.FAILED);
        entity.setProgress(100);
        entity.setErrorMessage(errorMessage);
        if (entity.getErrorCode() == null || entity.getErrorCode().isBlank()) {
            entity.setErrorCode(retryable ? "TASK_RETRYABLE" : "TASK_FAILED");
        }
        if (refundCredits) {
            refundTaskCredits(entity);
        }
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        updateById(entity);
    }

    @Override
    @Transactional
    public TaskItem retryTask(long taskId, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertMutableForViewer(entity, viewer);
        if (!TaskStatusCode.FAILED.equals(entity.getStatus())
                && !TaskStatusCode.RETRYABLE.equals(entity.getStatus())
                && !TaskStatusCode.CANCELED.equals(entity.getStatus())) {
            throw new BusinessException(40900, "当前任务状态不允许重试");
        }
        LocalDateTime now = LocalDateTime.now();
        int nextRetryCount = entity.getRetryCount() == null ? 1 : entity.getRetryCount() + 1;
        if (requiresCreditChange(entity)) {
            creditService.assertBalanceAtLeast(entity.getOwnerUserId(), entity.getCreditCost());
        }
        CreditChangeResult creditLog = consumeRetryCredits(entity, nextRetryCount);
        entity.setStatus(TaskStatusCode.QUEUED);
        entity.setProgress(0);
        entity.setOutputJson(null);
        entity.setResultAssetId(null);
        entity.setErrorCode(null);
        entity.setErrorMessage(null);
        entity.setResultViewed(0);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setRetryCount(nextRetryCount);
        if (creditLog != null) {
            entity.setCreditLogId(creditLog.creditLogId());
        }
        entity.setUpdatedAt(now);
        updateById(entity);
        return toItem(entity);
    }

    @Override
    @Transactional
    public TaskItem cancelTask(long taskId, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertMutableForViewer(entity, viewer);
        if (!TaskStatusCode.QUEUED.equals(entity.getStatus())
                && !TaskStatusCode.RUNNING.equals(entity.getStatus())) {
            throw new BusinessException(40900, "仅排队中或执行中的任务可取消");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(TaskStatusCode.CANCELED);
        entity.setProgress(100);
        refundTaskCredits(entity);
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        updateById(entity);
        return toItem(entity);
    }

    @Override
    @Transactional
    public TaskItem markTaskViewed(long taskId, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertMutableForViewer(entity, viewer);
        if (!TaskStatusCode.SUCCESS.equals(entity.getStatus())) {
            throw new BusinessException(40900, "仅成功的任务可标记已查看");
        }
        entity.setResultViewed(1);
        entity.setUpdatedAt(LocalDateTime.now());
        updateById(entity);
        return toItem(entity);
    }

    @Override
    public List<TaskItem> listTasks(OptionalLong viewerUserId, Long projectId, String taskType, String status,
                                    Integer pageNo, Integer pageSize) {
        if (projectId == null && viewerUserId.isEmpty()) {
            return List.of();
        }
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? PAGESIZE_DEFAULT : Math.min(pageSize, 100);
        LambdaQueryWrapper<TaskEntity> w = visibilityWrapper(viewerUserId, projectId)
                .orderByDesc(TaskEntity::getCreatedAt);
        if (taskType != null && !taskType.isBlank()) {
            w.eq(TaskEntity::getTaskType, taskType);
        }
        if (status != null && !status.isBlank()) {
            w.eq(TaskEntity::getStatus, status);
        }
        w.last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        return list(w).stream().map(this::toItem).collect(Collectors.toList());
    }

    @Override
    public TaskSummaryResponse getTaskSummary(OptionalLong viewerUserId, Long projectId) {
        if (projectId == null && viewerUserId.isEmpty()) {
            return new TaskSummaryResponse(0, 0, 0, List.of());
        }
        long processing = count(visibilityWrapper(viewerUserId, projectId)
                .in(TaskEntity::getStatus, TaskStatusCode.QUEUED, TaskStatusCode.RUNNING));
        long success = count(visibilityWrapper(viewerUserId, projectId)
                .eq(TaskEntity::getStatus, TaskStatusCode.SUCCESS));
        long failed = count(visibilityWrapper(viewerUserId, projectId)
                .in(TaskEntity::getStatus,
                        TaskStatusCode.FAILED, TaskStatusCode.RETRYABLE, TaskStatusCode.CANCELED));
        List<TaskItem> recent = listTasks(viewerUserId, projectId, null, null, 1, 10);
        return new TaskSummaryResponse(processing, success, failed, recent);
    }

    @Override
    public TaskItem getTask(long taskId) {
        return toItem(requireEntity(taskId));
    }

    @Override
    public TaskItem getTaskForViewer(long taskId, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertVisibleForViewer(entity, viewer);
        return toItem(entity);
    }

    private TaskEntity requireEntity(long taskId) {
        TaskEntity entity = super.getById(taskId);
        if (entity == null) {
            throw new BusinessException(40400, "任务不存在");
        }
        return entity;
    }

    /**
     * 有 projectId：该项目内 owner 为空的条目（演示/历史）+ 当前用户自己的任务。未登录：仅 owner 为空的条目。
     */
    private LambdaQueryWrapper<TaskEntity> visibilityWrapper(OptionalLong viewer, Long projectId) {
        if (projectId == null) {
            return new LambdaQueryWrapper<TaskEntity>().eq(TaskEntity::getOwnerUserId, viewer.getAsLong());
        }
        LambdaQueryWrapper<TaskEntity> w = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getProjectId, projectId);
        if (viewer.isPresent()) {
            long uid = viewer.getAsLong();
            w.and(q -> q.isNull(TaskEntity::getOwnerUserId).or().eq(TaskEntity::getOwnerUserId, uid));
        } else {
            w.isNull(TaskEntity::getOwnerUserId);
        }
        return w;
    }

    /**
     * 无 owner 的任务对所有人可见；有 owner 的须登录且为本人。
     */
    private void assertVisibleForViewer(TaskEntity entity, OptionalLong viewer) {
        Long owner = entity.getOwnerUserId();
        if (owner == null) {
            return;
        }
        if (viewer.isEmpty()) {
            throw new BusinessException(40100, "请先登录后查看该任务");
        }
        if (!owner.equals(viewer.getAsLong())) {
            throw new BusinessException(40300, "无权查看该任务");
        }
    }

    private void assertMutableForViewer(TaskEntity entity, OptionalLong viewer) {
        Long owner = entity.getOwnerUserId();
        if (owner == null) {
            return;
        }
        assertVisibleForViewer(entity, viewer);
    }

    private String resolveModelCode(String modelCode, String inputJson) {
        if (StringUtils.hasText(modelCode)) {
            return modelCode.trim();
        }
        if (!StringUtils.hasText(inputJson)) {
            return null;
        }
        try {
            Map<String, Object> input = objectMapper.readValue(inputJson, new TypeReference<>() {
            });
            Object value = input.get("modelCode");
            if (value == null) {
                value = input.get("model");
            }
            return value == null ? null : trimToNull(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long resolveCreditCost(String taskType, Long creditCost) {
        if (creditCost != null) {
            return Math.max(0L, creditCost);
        }
        return taskCreditProperties.costFor(taskType);
    }

    private String consumeIdempotencyKey(TaskEntity entity) {
        if (StringUtils.hasText(entity.getIdempotencyKey())) {
            return entity.getIdempotencyKey().trim();
        }
        return "AI_CONSUME:" + entity.getTaskId();
    }

    private CreditChangeResult consumeRetryCredits(TaskEntity entity, int retryCount) {
        if (!requiresCreditChange(entity)) {
            return null;
        }
        return creditService.consumeForTask(
                entity.getOwnerUserId(),
                entity.getTaskId(),
                entity.getModelCode(),
                entity.getCreditCost(),
                retryConsumeIdempotencyKey(entity, retryCount),
                "AI 任务重试预扣"
        );
    }

    private void refundTaskCredits(TaskEntity entity) {
        if (!requiresCreditChange(entity)) {
            return;
        }
        creditService.refundForTask(
                entity.getOwnerUserId(),
                entity.getTaskId(),
                entity.getModelCode(),
                entity.getCreditCost(),
                refundIdempotencyKey(entity),
                "AI 任务失败或取消退款"
        );
    }

    private boolean requiresCreditChange(TaskEntity entity) {
        return entity.getOwnerUserId() != null
                && entity.getTaskId() != null
                && entity.getCreditCost() != null
                && entity.getCreditCost() > 0;
    }

    private String retryConsumeIdempotencyKey(TaskEntity entity, int retryCount) {
        return "AI_CONSUME:" + entity.getTaskId() + ":RETRY:" + retryCount;
    }

    private String refundIdempotencyKey(TaskEntity entity) {
        int retryCount = entity.getRetryCount() == null ? 0 : entity.getRetryCount();
        if (retryCount <= 0) {
            return "AI_REFUND:" + entity.getTaskId();
        }
        return "AI_REFUND:" + entity.getTaskId() + ":RETRY:" + retryCount;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private TaskEntity findTaskByIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        LambdaQueryWrapper<TaskEntity> w = new LambdaQueryWrapper<>();
        w.eq(TaskEntity::getIdempotencyKey, idempotencyKey.trim()).last("limit 1");
        return getOne(w, false);
    }

    /**
     * 幂等键全局唯一：仅允许创建者或同为匿名任务复用返回。
     */
    private void assertIdempotencyKeyOwner(TaskEntity existing, Long ownerUserId) {
        Long rowOwner = existing.getOwnerUserId();
        if (ownerUserId == null) {
            if (rowOwner != null) {
                throw new BusinessException(40300, "该幂等键已绑定登录用户任务，匿名请求不可复用");
            }
            return;
        }
        if (rowOwner != null && !rowOwner.equals(ownerUserId)) {
            throw new BusinessException(40300, "该幂等键已被其他账号使用");
        }
    }

    private Long parseResultAssetId(String taskType, String outputJson) {
        if (outputJson == null || outputJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(outputJson, new TypeReference<>() {
            });
            if (map == null) {
                return null;
            }
            Object direct = map.get("resultAssetId");
            if (direct instanceof Number n) {
                return n.longValue();
            }
            if (TaskTypeCode.AVATAR_GENERATE.equals(taskType)) {
                Object ids = map.get("assetIds");
                if (ids instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number n) {
                    return n.longValue();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private TaskItem toItem(TaskEntity e) {
        return new TaskItem(
                e.getTaskId(),
                e.getProjectId(),
                e.getOwnerUserId(),
                e.getTaskType(),
                e.getModelCode(),
                e.getCreditCost(),
                e.getCreditLogId(),
                resolveTaskTitle(e),
                e.getStatus(),
                e.getProgress(),
                e.getResultAssetId(),
                e.getErrorCode(),
                e.getErrorMessage(),
                e.getRetryCount(),
                e.getResultViewed() != null && e.getResultViewed() != 0,
                e.getInputJson(),
                e.getOutputJson(),
                e.getTraceId(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getStartedAt(),
                e.getFinishedAt()
        );
    }

    private String resolveTaskTitle(TaskEntity e) {
        String type = e.getTaskType();
        if (TaskTypeCode.VIDEO_PARSE.equals(type)) {
            return "视频解析";
        }
        if (TaskTypeCode.SCRIPT_REWRITE.equals(type)) {
            return "文案改写";
        }
        if (TaskTypeCode.STORYBOARD_GENERATE.equals(type)) {
            return "分镜生成";
        }
        if (TaskTypeCode.TTS_GENERATE.equals(type)) {
            return "语音合成";
        }
        if (TaskTypeCode.VOICE_SAMPLE.equals(type)) {
            String voiceName = resolveVoiceNameFromInputJson(e.getInputJson());
            return voiceName == null || voiceName.isBlank() ? "音色试听" : "音色试听-" + voiceName;
        }
        if (TaskTypeCode.AVATAR_GENERATE.equals(type)) {
            return "形象写真生成";
        }
        if (TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(type)) {
            return "数字人口播生成";
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(type)) {
            return "抖音对标解析与转写";
        }
        return type == null ? "任务" : type;
    }

    private String resolveVoiceNameFromInputJson(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(inputJson, new TypeReference<>() {
            });
            Object voiceIdObj = m.get("voiceId");
            if (voiceIdObj == null) {
                return null;
            }
            long voiceId;
            if (voiceIdObj instanceof Number n) {
                voiceId = n.longValue();
            } else {
                voiceId = Long.parseLong(String.valueOf(voiceIdObj));
            }
            if (voiceId <= 0) {
                return null;
            }
            VoiceProfileEntity voice = voiceProfileMapper.selectById(voiceId);
            return voice == null ? null : voice.getVoiceName();
        } catch (Exception ignored) {
            return null;
        }
    }
}
