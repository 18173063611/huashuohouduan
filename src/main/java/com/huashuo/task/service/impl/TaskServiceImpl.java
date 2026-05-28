package com.huashuo.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.model.SettlementStatus;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.billing.service.BillingStepConfigService;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.billing.service.UsageEstimateService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.config.AiTaskProperties;
import com.huashuo.task.config.TaskCreditProperties;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.limit.AiTaskUserRateLimiter;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.mq.AiTaskQueueNames;
import com.huashuo.task.service.TaskResultAssetService;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.task.vo.TaskResultResponse;
import com.huashuo.task.vo.TaskSummaryResponse;
import com.huashuo.task.ws.TaskNotificationService;
import com.huashuo.user.service.CreditChangeResult;
import com.huashuo.user.service.CreditService;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * 任务台账：创建占位、运行中/成功/失败更新、重试与列表查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, TaskEntity> implements TaskService {

    private static final int PAGESIZE_DEFAULT = 10;

    private final ObjectMapper objectMapper;
    private final VoiceProfileMapper voiceProfileMapper;
    private final CreditService creditService;
    private final TaskCreditProperties taskCreditProperties;
    private final AiTaskProperties aiTaskProperties;
    private final AiTaskUserRateLimiter aiTaskUserRateLimiter;
    private final TaskNotificationService taskNotificationService;
    private final UsageEstimateService usageEstimateService;
    private final CreditBillingService creditBillingService;
    private final BillingStepConfigService billingStepConfigService;
    private final BillingEstimateService billingEstimateService;
    private final TaskResultAssetService taskResultAssetService;

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
        // 第一版预扣金额来源（按优先级，从高到低）：
        //   1) 调用方显式传入的 creditCost（极少使用，例如自定义补扣场景）；
        //   2) ai_billing_step_config 中该 task_type 所有 enabled=1 步骤的 credit_cost 之和；
        //   3) TaskCreditProperties.costFor(taskType) 兜底（仅当 step config 为空时）。
        // ai_model_price 在 createTask 阶段不参与预扣金额计算，仅在 settle 阶段用于实际用量结算。
        // 这样可以保证：管理员在后台编辑 ai_billing_step_config 后，AVATAR / DIGITAL_HUMAN 等所有 task_type
        // 的预扣金额都立即同步；避免出现「step 改了但预扣没变」的双源冲突。
        long fixedCreditCost = resolveCreditCost(taskType, creditCost);
        UsageEstimateResult priceEstimate = usageEstimateService.estimate(taskType, normalizedModelCode, inputJson, fixedCreditCost);
        long resolvedCreditCost = fixedCreditCost;
        // 保留 ai_model_price 提供的元数据（provider / modelCode / usageUnit / 估算 usage 与 token 数），但强制把
        // estimatedCreditCost 覆写为 step 汇总，确保 precharge 与 ai_usage_log 的 estimated_credit_cost 严格一致。
        UsageEstimateResult estimate = new UsageEstimateResult(
                priceEstimate.provider(),
                priceEstimate.modelCode(),
                priceEstimate.usageUnit(),
                priceEstimate.estimatedUsage(),
                priceEstimate.estimatedPromptTokens(),
                priceEstimate.estimatedCompletionTokens(),
                resolvedCreditCost
        );
        String idempotency = normalizeIdempotencyKey(idempotencyKey);
        if (idempotency != null) {
            TaskEntity existing = findTaskByIdempotencyKey(idempotency);
            if (existing != null) {
                assertIdempotencyKeyOwner(existing, ownerUserId);
                return toItem(existing);
            }
        }
        if (resolvedCreditCost > 0 && ownerUserId == null) {
            log.warn("createTask rejected: paid task without owner. taskType={}, traceId={}, idempotencyKey={}, projectId={}",
                    taskType, traceId, idempotency, projectId);
            throw new BusinessException(40100, "创建消耗积分任务失败：缺少登录用户信息");
        }
        AiTaskUserRateLimiter.Reservation userLimitReservation = assertTaskAdmissionAllowed(taskType, ownerUserId);
        if (resolvedCreditCost > 0 && ownerUserId != null) {
            try {
                creditService.assertBalanceAtLeast(ownerUserId, resolvedCreditCost);
            } catch (RuntimeException ex) {
                aiTaskUserRateLimiter.release(userLimitReservation);
                throw ex;
            }
        }
        TaskEntity entity = new TaskEntity();
        entity.setProjectId(projectId);
        entity.setOwnerUserId(ownerUserId);
        entity.setTaskType(taskType);
        entity.setModelCode(StringUtils.hasText(estimate.modelCode()) ? estimate.modelCode() : normalizedModelCode);
        entity.setProvider(estimate.provider());
        entity.setUsageUnit(estimate.usageUnit());
        entity.setEstimatedUsage(estimate.estimatedUsage());
        entity.setActualUsage(null);
        entity.setEstimatedCreditCost(resolvedCreditCost);
        entity.setActualCreditCost(0L);
        entity.setSettlementStatus(resolvedCreditCost > 0 ? SettlementStatus.PRECHARGED : SettlementStatus.NONE);
        entity.setCreditCost(resolvedCreditCost);
        entity.setCreditLogId(null);
        entity.setQueueName(resolveQueueName(taskType));
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
            aiTaskUserRateLimiter.release(userLimitReservation);
            if (idempotency != null) {
                TaskEntity raced = findTaskByIdempotencyKey(idempotency);
                if (raced != null) {
                    assertIdempotencyKeyOwner(raced, ownerUserId);
                    return toItem(raced);
                }
            }
            throw ex;
        } catch (RuntimeException ex) {
            aiTaskUserRateLimiter.release(userLimitReservation);
            throw ex;
        }
        confirmUserLimitAfterCommit(userLimitReservation, entity.getTaskId());
        if (resolvedCreditCost > 0) {
            try {
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
            } catch (RuntimeException ex) {
                aiTaskUserRateLimiter.release(userLimitReservation);
                throw ex;
            }
        }
        // 估算占位行：第一版仅 estimated_credit_cost 与 usage_unit 等字段，actual_* 留待 settle 时补；
        // 失败不阻塞任务创建（usage_log 仅做对账，写入异常时打日志由 BaseMapper 抛出由事务回滚）。
        creditBillingService.recordEstimate(ownerUserId, entity.getTaskId(), taskType, estimate);
        log.info("AI task created taskId={} taskType={} ownerUserId={} queueName={} creditCost={} traceId={} idempotencyKey={}",
                entity.getTaskId(), entity.getTaskType(), entity.getOwnerUserId(), entity.getQueueName(),
                entity.getCreditCost(), entity.getTraceId(), entity.getIdempotencyKey());
        notifyAfterCommit(entity);
        return toItem(entity);
    }

    @Override
    @Transactional
    public void startTask(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<TaskEntity> claim = new LambdaUpdateWrapper<>();
        claim.eq(TaskEntity::getTaskId, taskId)
                .in(TaskEntity::getStatus, TaskStatusCode.QUEUED, TaskStatusCode.RETRYABLE)
                .set(TaskEntity::getStatus, TaskStatusCode.RUNNING)
                .set(TaskEntity::getProgress, 10)
                .set(TaskEntity::getStartedAt, now)
                .set(TaskEntity::getFinishedAt, null)
                .set(TaskEntity::getErrorCode, null)
                .set(TaskEntity::getErrorMessage, null)
                .set(TaskEntity::getUpdatedAt, now);
        if (!update(claim)) {
            TaskEntity current = requireEntity(taskId);
            log.warn("AI task claim rejected taskId={} currentStatus={} retryCount={}",
                    taskId, current.getStatus(), current.getRetryCount());
            throw new BusinessException(40900, "任务已被其他消费者抢占或当前状态不允许开始执行: " + current.getStatus());
        }
        log.info("AI task claimed taskId={} status={} progress={}", taskId, TaskStatusCode.RUNNING, 10);
        notifyAfterCommit(requireEntity(taskId));
    }

    @Override
    @Transactional
    public void updateTaskProgress(long taskId, int progress) {
        updateTaskProgress(taskId, progress, null);
    }

    @Override
    @Transactional
    public void updateTaskProgress(long taskId, int progress, String outputJson) {
        TaskEntity entity = requireEntity(taskId);
        if (!TaskStatusCode.RUNNING.equals(entity.getStatus())) {
            log.debug("AI task progress ignored taskId={} status={} requestedProgress={}",
                    taskId, entity.getStatus(), progress);
            return;
        }
        int clamped = Math.max(0, Math.min(100, progress));
        int current = entity.getProgress() == null ? 0 : entity.getProgress();
        if (clamped < current) {
            log.debug("AI task progress ignored taskId={} currentProgress={} requestedProgress={}",
                    taskId, current, clamped);
            return;
        }
        entity.setProgress(clamped);
        if (StringUtils.hasText(outputJson)) {
            entity.setOutputJson(outputJson.trim());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        updateById(entity);
        log.info("AI task progress updated taskId={} progress={} previousProgress={} hasPartialOutput={}",
                taskId, clamped, current, StringUtils.hasText(outputJson));
        notifyAfterCommit(entity);
    }

    @Override
    @Transactional
    public void completeTask(long taskId, String outputJson) {
        TaskEntity entity = requireEntity(taskId);
        if (!TaskStatusCode.RUNNING.equals(entity.getStatus())) {
            throw new BusinessException(40900, "任务状态不允许标记成功");
        }
        LocalDateTime now = LocalDateTime.now();
        TaskResultAssetService.ResultAsset resultAsset = taskResultAssetService.ensureJsonResultAsset(entity, outputJson);
        String completedOutputJson = resultAsset.outputJson();
        entity.setStatus(TaskStatusCode.SUCCESS);
        entity.setProgress(100);
        entity.setOutputJson(completedOutputJson);
        entity.setErrorMessage(null);
        entity.setErrorCode(null);
        entity.setResultAssetId(parseResultAssetId(entity.getTaskType(), completedOutputJson));
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        updateById(entity);
        log.info("AI task completed taskId={} taskType={} resultAssetId={} creditCost={} actualCreditCost={}",
                taskId, entity.getTaskType(), entity.getResultAssetId(), entity.getCreditCost(),
                entity.getActualCreditCost());
        notifyAfterCommit(entity);
        releaseUserLimitAfterCommit(entity);
    }

    @Override
    @Transactional
    public TaskItem replaceSuccessfulTaskResult(long taskId, String outputJson, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertMutableForViewer(entity, viewer);
        if (!TaskStatusCode.SUCCESS.equals(entity.getStatus())) {
            throw new BusinessException(40900, "Only successful tasks can replace result");
        }
        if (!StringUtils.hasText(outputJson)) {
            throw new BusinessException(40000, "Task result must not be empty");
        }
        TaskResultAssetService.ResultAsset resultAsset = taskResultAssetService.ensureJsonResultAsset(entity, outputJson);
        String updatedOutputJson = resultAsset.outputJson();
        entity.setOutputJson(updatedOutputJson);
        entity.setResultAssetId(parseResultAssetId(entity.getTaskType(), updatedOutputJson));
        entity.setResultViewed(0);
        entity.setUpdatedAt(LocalDateTime.now());
        updateById(entity);
        log.info("AI task result replaced taskId={} taskType={} resultAssetId={}",
                taskId, entity.getTaskType(), entity.getResultAssetId());
        notifyAfterCommit(entity);
        return toItem(entity);
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
            entity.setActualCreditCost(0L);
            entity.setSettlementStatus(SettlementStatus.REFUNDED);
        }
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        updateById(entity);
        log.warn("AI task failed taskId={} taskType={} retryable={} refundCredits={} errorCode={} message={}",
                taskId, entity.getTaskType(), retryable, refundCredits, entity.getErrorCode(), errorMessage);
        if (!refundCredits) {
            // 第三方已受理 / 已产生费用：预扣不退款，但必须把任务从 PRECHARGED 推进到 SETTLED 终态，
            // 并补一条 usage_phase=ACTUAL 占位记录（actual_credit_cost = estimated_credit_cost），
            // 让对账、统计报表能唯一识别"已消费但失败、不退款"的任务（否则会和真排队中任务混在 PRECHARGED）。
            // 注：必须在 updateById(entity) 之后执行——recordConsumeWithoutRefund 内部按 task_id 查最新行后再写回。
            creditBillingService.recordConsumeWithoutRefund(taskId, errorMessage);
        }
        notifyAfterCommit(entity);
        if (!retryable) {
            releaseUserLimitAfterCommit(entity);
        }
    }

    @Override
    @Transactional
    public void incrementRetryCount(long taskId) {
        TaskEntity entity = requireEntity(taskId);
        entity.setRetryCount(entity.getRetryCount() == null ? 1 : entity.getRetryCount() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        updateById(entity);
        log.info("AI task retry count incremented taskId={} retryCount={}", taskId, entity.getRetryCount());
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
        log.info("AI task retry requested taskId={} taskType={} retryCount={} creditCost={}",
                taskId, entity.getTaskType(), entity.getRetryCount(), entity.getCreditCost());
        notifyAfterCommit(entity);
        return toItem(entity);
    }

    @Override
    @Transactional
    public TaskItem cancelTask(long taskId, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertMutableForViewer(entity, viewer);
        if (!TaskStatusCode.QUEUED.equals(entity.getStatus())
                && !TaskStatusCode.RUNNING.equals(entity.getStatus())
                && !TaskStatusCode.RETRYABLE.equals(entity.getStatus())) {
            throw new BusinessException(40900, "仅排队中或执行中的任务可取消");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(TaskStatusCode.CANCELED);
        entity.setProgress(100);
        entity.setErrorCode("TASK_CANCELED");
        entity.setErrorMessage(null);
        refundTaskCredits(entity);
        entity.setActualCreditCost(0L);
        entity.setSettlementStatus(SettlementStatus.REFUNDED);
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        updateById(entity);
        log.info("AI task canceled taskId={} taskType={} ownerUserId={} creditCost={}",
                taskId, entity.getTaskType(), entity.getOwnerUserId(), entity.getCreditCost());
        notifyAfterCommit(entity);
        releaseUserLimitAfterCommit(entity);
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
        return toLightweightItem(entity);
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
                .select(TaskEntity::getTaskId,
                        TaskEntity::getProjectId,
                        TaskEntity::getOwnerUserId,
                        TaskEntity::getTaskType,
                        TaskEntity::getModelCode,
                        TaskEntity::getProvider,
                        TaskEntity::getUsageUnit,
                        TaskEntity::getEstimatedUsage,
                        TaskEntity::getActualUsage,
                        TaskEntity::getEstimatedCreditCost,
                        TaskEntity::getActualCreditCost,
                        TaskEntity::getSettlementStatus,
                        TaskEntity::getCreditCost,
                        TaskEntity::getCreditLogId,
                        TaskEntity::getResultAssetId,
                        TaskEntity::getStatus,
                        TaskEntity::getProgress,
                        TaskEntity::getErrorCode,
                        TaskEntity::getErrorMessage,
                        TaskEntity::getRetryCount,
                        TaskEntity::getResultViewed,
                        TaskEntity::getTraceId,
                        TaskEntity::getStartedAt,
                        TaskEntity::getFinishedAt,
                        TaskEntity::getCreatedAt,
                        TaskEntity::getUpdatedAt)
                .orderByDesc(TaskEntity::getCreatedAt);
        if (taskType != null && !taskType.isBlank()) {
            w.eq(TaskEntity::getTaskType, taskType);
        }
        if (status != null && !status.isBlank()) {
            w.eq(TaskEntity::getStatus, status);
        }
        w.last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        return list(w).stream().map(this::toLightweightItem).collect(Collectors.toList());
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
        return toLightweightItem(entity);
    }

    @Override
    public TaskResultResponse getTaskResultForViewer(long taskId, OptionalLong viewer) {
        TaskEntity entity = requireEntity(taskId);
        assertVisibleForViewer(entity, viewer);
        return new TaskResultResponse(
                entity.getTaskId(),
                entity.getProjectId(),
                entity.getOwnerUserId(),
                entity.getTaskType(),
                resolveTaskTitle(entity),
                entity.getStatus(),
                entity.getProgress(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                parseOutputJson(entity.getOutputJson())
        );
    }

    private TaskEntity requireEntity(long taskId) {
        TaskEntity entity = super.getById(taskId);
        if (entity == null) {
            throw new BusinessException(40400, "任务不存在");
        }
        return entity;
    }

    private void notifyAfterCommit(TaskEntity entity) {
        TaskItem item = toLightweightItem(entity);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskNotificationService.notifyTaskChanged(item);
                }
            });
            return;
        }
        taskNotificationService.notifyTaskChanged(item);
    }

    private void confirmUserLimitAfterCommit(AiTaskUserRateLimiter.Reservation reservation, Long taskId) {
        if (reservation == null || !reservation.active()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiTaskUserRateLimiter.confirm(reservation, taskId);
                }
            });
            return;
        }
        aiTaskUserRateLimiter.confirm(reservation, taskId);
    }

    private void releaseUserLimitAfterCommit(TaskEntity entity) {
        if (entity == null || entity.getOwnerUserId() == null || entity.getTaskId() == null) {
            return;
        }
        Long ownerUserId = entity.getOwnerUserId();
        Long taskId = entity.getTaskId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiTaskUserRateLimiter.release(ownerUserId, taskId);
                }
            });
            return;
        }
        aiTaskUserRateLimiter.release(ownerUserId, taskId);
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

    private AiTaskUserRateLimiter.Reservation assertTaskAdmissionAllowed(String taskType, Long ownerUserId) {
        AiTaskProperties.Limits limits = aiTaskProperties.getLimits();
        if (!limits.isEnabled()) {
            return AiTaskUserRateLimiter.Reservation.noop();
        }

        long globalActive = count(activeTaskWrapper());
        if (globalActive >= limits.getMaxActiveGlobal()) {
            throw new BusinessException(42900, "TASK_ALREADY_RUNNING");
        }

        if (ownerUserId == null) {
            return AiTaskUserRateLimiter.Reservation.noop();
        }

        long userActive = count(activeTaskWrapper().eq(TaskEntity::getOwnerUserId, ownerUserId));
        if (userActive >= limits.getMaxActivePerUser()) {
            throw new BusinessException(42900, "TASK_ALREADY_RUNNING");
        }

        if (isHeavyTask(taskType)) {
            long userHeavyActive = count(activeTaskWrapper()
                    .eq(TaskEntity::getOwnerUserId, ownerUserId)
                    .in(TaskEntity::getTaskType,
                            TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT,
                            TaskTypeCode.SEEDANCE_TEXT_VIDEO,
                            TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO,
                            TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO,
                            TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                            TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO,
                            TaskTypeCode.DIGITAL_HUMAN_GENERATE));
            if (userHeavyActive >= limits.getMaxActiveHeavyPerUser()) {
                throw new BusinessException(42900, "TASK_ALREADY_RUNNING");
            }
        }
        return aiTaskUserRateLimiter.reserve(ownerUserId, limits.getMaxActivePerUser());
    }

    private LambdaQueryWrapper<TaskEntity> activeTaskWrapper() {
        return new LambdaQueryWrapper<TaskEntity>()
                .in(TaskEntity::getStatus,
                        TaskStatusCode.QUEUED,
                        TaskStatusCode.RUNNING,
                        TaskStatusCode.RETRYABLE);
    }

    private boolean isHeavyTask(String taskType) {
        return TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)
                || TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(taskType)
                || TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(taskType);
    }

    private String resolveQueueName(String taskType) {
        if (TaskTypeCode.TTS_GENERATE.equals(taskType)
                || TaskTypeCode.VOICE_SAMPLE.equals(taskType)) {
            return AiTaskQueueNames.TTS_GENERATE_QUEUE;
        }
        if (TaskTypeCode.AVATAR_GENERATE.equals(taskType)) {
            return AiTaskQueueNames.AVATAR_GENERATE_QUEUE;
        }
        if (TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(taskType)
                || TaskTypeCode.DOUYIN_REWRITE.equals(taskType)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)) {
            return AiTaskQueueNames.WRITER_QUEUE;
        }
        if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(taskType)
                || TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(taskType)) {
            return AiTaskQueueNames.VIDEO_GENERATE_QUEUE;
        }
        if (TaskTypeCode.QUICK_RENDER.equals(taskType)) {
            return AiTaskQueueNames.QUICK_RENDER_QUEUE;
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)) {
            return AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_QUEUE;
        }
        return AiTaskQueueNames.QUEUE;
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

    /**
     * 任务总积分解析委托给 {@link BillingEstimateService#resolveCreditCost(String, Long)}，
     * 与前端 {@code GET /api/v1/billing/estimate} 复用同一份逻辑，避免"展示 5 实扣 20"的双源冲突。
     */
    private long resolveCreditCost(String taskType, Long creditCost) {
        return billingEstimateService.resolveCreditCost(taskType, creditCost);
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

    private String normalizeIdempotencyKey(String value) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > 120) {
            throw new BusinessException(40000, "Idempotency key cannot exceed 120 characters");
        }
        return normalized;
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
        if (rowOwner == null) {
            throw new BusinessException(40300, "该幂等键已绑定匿名任务，登录请求不可复用");
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

    private Object parseOutputJson(String outputJson) {
        if (outputJson == null || outputJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(outputJson);
        } catch (Exception ignored) {
            return outputJson;
        }
    }

    private TaskItem toItem(TaskEntity e) {
        return toItem(e, true);
    }

    private TaskItem toLightweightItem(TaskEntity e) {
        return toItem(e, false);
    }

    private TaskItem toItem(TaskEntity e, boolean includePayload) {
        return new TaskItem(
                e.getTaskId(),
                e.getProjectId(),
                e.getOwnerUserId(),
                e.getTaskType(),
                e.getModelCode(),
                e.getProvider(),
                e.getUsageUnit(),
                e.getEstimatedUsage(),
                e.getActualUsage(),
                e.getEstimatedCreditCost(),
                e.getActualCreditCost(),
                e.getSettlementStatus(),
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
                includePayload ? e.getInputJson() : null,
                includePayload ? e.getOutputJson() : null,
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
        if (TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(type)) {
            return "视频分镜解析";
        }
        if (TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(type)) {
            return "链接视频分镜解析";
        }
        if (TaskTypeCode.DOUYIN_REWRITE.equals(type)) {
            return "抖音文案改写";
        }
        if (TaskTypeCode.DOUYIN_TRANSCRIPT.equals(type)) {
            return "抖音视频转写";
        }
        if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(type)) {
            return "文生视频";
        }
        if (TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(type)) {
            return "首帧图生视频";
        }
        if (TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(type)) {
            return "首尾帧图生视频";
        }
        if (TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(type)) {
            return "参考图生视频";
        }
        if (TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(type)) {
            return "汽车销售成片";
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(type)) {
            return "对标解析与转写";
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
