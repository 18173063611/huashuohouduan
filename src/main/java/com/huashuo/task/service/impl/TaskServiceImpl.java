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
import com.huashuo.task.vo.CarSalesTestBatchReport;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final int TEST_BATCH_REPORT_SCAN_LIMIT = 1000;

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
        // 预扣金额来源（按优先级，从高到低）：
        //   1) 调用方显式传入的 creditCost（极少使用，例如自定义补扣场景）；
        //   2) TTS / 试听 / 形象生成等可从输入推导真实用量的任务，按 ai_model_price 动态预估；
        //   3) 其余任务按 ai_billing_step_config 中 enabled=1 步骤的 credit_cost 之和；
        //   4) TaskCreditProperties.costFor(taskType) 兜底（仅当 step config 为空时）。
        // 这样既保留后台步骤计费的统一管理，又让字符数、图片张数这类任务的预扣更接近完成后的实际结算。
        long fixedCreditCost = resolveCreditCost(taskType, creditCost);
        UsageEstimateResult priceEstimate = usageEstimateService.estimate(taskType, normalizedModelCode, inputJson, fixedCreditCost);
        long resolvedCreditCost = resolvePrechargeCreditCost(taskType, creditCost, fixedCreditCost, priceEstimate);
        // 保留 ai_model_price 提供的元数据（provider / modelCode / usageUnit / 估算 usage 与 token 数）。
        // 固定步骤任务继续按 step/properties 汇总预扣；TTS / 图片等可从输入直接推导真实用量的任务，
        // 使用模型单价动态预估，让预扣更接近 settle 的实际结算。
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
        entity.setOutputJson(mergeFailureDiagnostics(entity, errorMessage, retryable, refundCredits, now));
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
    public CarSalesTestBatchReport getCarSalesTestBatchReport(OptionalLong viewerUserId, Long projectId,
                                                              String testBatch) {
        String normalizedBatch = trimToNull(testBatch);
        if (normalizedBatch == null) {
            throw new BusinessException(40000, "testBatch must not be blank");
        }
        if (projectId == null && viewerUserId.isEmpty()) {
            return emptyCarSalesTestBatchReport(normalizedBatch, projectId);
        }

        LambdaQueryWrapper<TaskEntity> w = visibilityWrapper(viewerUserId, projectId)
                .in(TaskEntity::getTaskType, TaskTypeCode.QUICK_RENDER, TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO)
                .orderByDesc(TaskEntity::getCreatedAt)
                .last("LIMIT " + TEST_BATCH_REPORT_SCAN_LIMIT);
        List<CarSalesTestBatchReport.SampleItem> samples = list(w).stream()
                .map(entity -> buildCarSalesTestSampleItem(entity, normalizedBatch))
                .filter(item -> item != null)
                .collect(Collectors.toList());

        int successCount = (int) samples.stream()
                .filter(item -> TaskStatusCode.SUCCESS.equals(item.status()))
                .count();
        int failedCount = (int) samples.stream()
                .filter(item -> isFailureStatus(item.status()))
                .count();
        int processingCount = (int) samples.stream()
                .filter(item -> TaskStatusCode.QUEUED.equals(item.status()) || TaskStatusCode.RUNNING.equals(item.status()))
                .count();
        int retryableCount = (int) samples.stream()
                .filter(item -> TaskStatusCode.RETRYABLE.equals(item.status()))
                .count();
        int quickRenderTaskCount = (int) samples.stream()
                .filter(item -> TaskTypeCode.QUICK_RENDER.equals(item.taskType()))
                .count();
        int generationTaskCount = (int) samples.stream()
                .filter(item -> TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(item.taskType()))
                .count();
        int sampleCount = samples.stream()
                .map(item -> firstText(item.sampleId(), item.taskId() == null ? null : "task-" + item.taskId()))
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .size();
        Map<String, Long> failureCategoryCounts = samples.stream()
                .filter(item -> isFailureStatus(item.status()))
                .collect(Collectors.groupingBy(
                        item -> firstText(item.errorCategory(), "unknown"),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        return new CarSalesTestBatchReport(
                normalizedBatch,
                projectId,
                samples.size(),
                sampleCount,
                quickRenderTaskCount,
                generationTaskCount,
                successCount,
                failedCount,
                processingCount,
                retryableCount,
                failureCategoryCounts,
                samples
        );
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

    private CarSalesTestBatchReport emptyCarSalesTestBatchReport(String testBatch, Long projectId) {
        return new CarSalesTestBatchReport(
                testBatch,
                projectId,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                List.of()
        );
    }

    private CarSalesTestBatchReport.SampleItem buildCarSalesTestSampleItem(TaskEntity entity, String expectedBatch) {
        Map<String, Object> input = readJsonAsMap(entity.getInputJson());
        Map<String, Object> output = readJsonAsMap(entity.getOutputJson());
        Map<String, Object> outputInput = mapValue(output.get("input"));
        Map<String, Object> failureDiagnostics = mapValue(output.get("failureDiagnostics"));
        Map<String, Object> failureInputSummary = mapValue(failureDiagnostics.get("inputSummary"));
        String actualBatch = firstText(
                textValue(input.get("testBatch")),
                textValue(output.get("testBatch")),
                textValue(outputInput.get("testBatch")),
                textValue(failureInputSummary.get("testBatch"))
        );
        if (!expectedBatch.equals(actualBatch)) {
            return null;
        }

        String traceId = firstText(
                textValue(output.get("traceId")),
                textValue(failureDiagnostics.get("traceId")),
                entity.getTraceId()
        );
        String errorCode = firstText(
                textValue(failureDiagnostics.get("errorCode")),
                entity.getErrorCode()
        );
        String errorMessage = firstText(
                textValue(failureDiagnostics.get("errorMessage")),
                entity.getErrorMessage()
        );
        return new CarSalesTestBatchReport.SampleItem(
                entity.getTaskId(),
                entity.getProjectId(),
                entity.getOwnerUserId(),
                entity.getTaskType(),
                resolveTaskTitle(entity),
                entity.getStatus(),
                entity.getProgress(),
                entity.getResultAssetId(),
                firstText(
                        textValue(input.get("sampleId")),
                        textValue(output.get("sampleId")),
                        textValue(outputInput.get("sampleId")),
                        textValue(failureInputSummary.get("sampleId"))
                ),
                firstText(
                        textValue(input.get("outputPurpose")),
                        textValue(output.get("outputPurpose")),
                        textValue(outputInput.get("outputPurpose")),
                        textValue(failureInputSummary.get("outputPurpose"))
                ),
                firstText(
                        textValue(output.get("errorCategory")),
                        textValue(failureDiagnostics.get("errorCategory"))
                ),
                firstText(
                        textValue(output.get("failureType")),
                        textValue(failureDiagnostics.get("failureType"))
                ),
                textValue(failureDiagnostics.get("failureReason")),
                traceId,
                errorCode,
                errorMessage,
                booleanValue(failureDiagnostics.get("retryable")),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private boolean isFailureStatus(String status) {
        return TaskStatusCode.FAILED.equals(status)
                || TaskStatusCode.RETRYABLE.equals(status)
                || TaskStatusCode.CANCELED.equals(status);
    }

    private Map<String, Object> readJsonAsMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return mapValue(parsed);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                normalized.put(String.valueOf(key), item);
            }
        });
        return normalized;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String textValue(Object value) {
        if (value instanceof String string) {
            return trimToNull(string);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && StringUtils.hasText(string)) {
            return Boolean.parseBoolean(string.trim());
        }
        return null;
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
     * 解析固定步骤部分的预扣基准；动态用量任务会在 {@link #resolvePrechargeCreditCost(String, Long, long, UsageEstimateResult)}
     * 中按模型单价进一步贴近实际用量。
     */
    private long resolveCreditCost(String taskType, Long creditCost) {
        return billingEstimateService.resolveCreditCost(taskType, creditCost);
    }

    private long resolvePrechargeCreditCost(String taskType, Long creditCostOverride, long fixedCreditCost,
                                            UsageEstimateResult priceEstimate) {
        if (creditCostOverride != null) {
            return Math.max(0L, creditCostOverride);
        }
        if (priceEstimate != null
                && priceEstimate.estimatedCreditCost() > 0
                && supportsUsageBasedPrecharge(taskType)) {
            return Math.max(0L, priceEstimate.estimatedCreditCost());
        }
        return Math.max(0L, fixedCreditCost);
    }

    private boolean supportsUsageBasedPrecharge(String taskType) {
        String normalized = taskType == null ? null : taskType.trim().toUpperCase();
        return TaskTypeCode.TTS_GENERATE.equals(normalized)
                || TaskTypeCode.VOICE_SAMPLE.equals(normalized)
                || TaskTypeCode.AVATAR_GENERATE.equals(normalized);
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

    private String mergeFailureDiagnostics(TaskEntity entity, String errorMessage, boolean retryable,
                                           boolean refundCredits, LocalDateTime failedAt) {
        Map<String, Object> root = readOutputJsonAsMap(entity.getOutputJson());
        Map<String, Object> diagnostics = buildFailureDiagnostics(entity, errorMessage, retryable, refundCredits, failedAt);
        root.put("failureDiagnostics", diagnostics);
        root.put("errorCategory", diagnostics.get("errorCategory"));
        root.put("failureType", diagnostics.get("failureType"));
        root.put("traceId", diagnostics.get("traceId"));
        root.put("taskId", diagnostics.get("taskId"));
        return writeJsonOrFallback(root, entity.getOutputJson());
    }

    private Map<String, Object> readOutputJsonAsMap(String outputJson) {
        if (StringUtils.hasText(outputJson)) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(outputJson, new TypeReference<>() {
                });
                if (parsed != null) {
                    return new java.util.LinkedHashMap<>(parsed);
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(outputJson)) {
            root.put("rawOutput", outputJson);
        }
        return root;
    }

    private Map<String, Object> buildFailureDiagnostics(TaskEntity entity, String errorMessage, boolean retryable,
                                                        boolean refundCredits, LocalDateTime failedAt) {
        FailureClassification classification = classifyFailure(entity == null ? null : entity.getTaskType(), errorMessage);
        Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
        diagnostics.put("errorCategory", classification.category());
        diagnostics.put("failureType", retryable ? "retryable" : "permanent");
        diagnostics.put("failureReason", classification.reason());
        diagnostics.put("userAdvice", classification.userAdvice());
        diagnostics.put("taskId", entity == null ? null : entity.getTaskId());
        diagnostics.put("taskType", entity == null ? null : entity.getTaskType());
        diagnostics.put("traceId", entity == null ? null : trimToNull(entity.getTraceId()));
        diagnostics.put("errorCode", entity == null ? null : trimToNull(entity.getErrorCode()));
        diagnostics.put("errorMessage", trimToNull(errorMessage));
        diagnostics.put("retryable", retryable);
        diagnostics.put("refundCredits", refundCredits);
        diagnostics.put("failedAt", failedAt == null ? null : failedAt.toString());
        diagnostics.put("inputSummary", failureInputSummary(entity));
        return diagnostics;
    }

    private FailureClassification classifyFailure(String taskType, String errorMessage) {
        String text = (errorMessage == null ? "" : errorMessage).trim().toLowerCase();
        String type = taskType == null ? "" : taskType.trim();
        if (containsAny(text, "至少需要", "素材资产不存在", "车辆图片", "图片过少", "image",
                "缺少可下载 url", "下载失败", "download failed", "source video download", "assetid")) {
            return new FailureClassification("material", "素材问题",
                    "建议检查素材是否存在、图片是否清晰可访问，必要时替换为单车主体图后重试。");
        }
        if (containsAny(text, "参数", "request", "payload", "inputjson", "解析失败", "invalid",
                "不支持", "unsupported", "字段", "不能为空")) {
            return new FailureClassification("parameter", "参数问题",
                    "建议保留默认参数后重试；如仍失败，请保留任务编号和 traceId 交给开发排查。");
        }
        if (containsAny(text, "tos", "objectkey", "storage", "保存", "入库", "私有资产", "upload",
                "generated asset content fetch failed")) {
            return new FailureClassification("storage", "存储问题",
                    "结果可能已生成但保存或入库失败，建议稍后重试，并保留 objectKey/任务编号排查存储日志。");
        }
        if (containsAny(text, "ffmpeg", "合成", "拼接", "混剪", "字幕烧录", "bgm 混音", "后处理",
                "stitch", "compose", "post")) {
            return new FailureClassification("post_processing", "后处理问题",
                    "视频生成可能已成功但后处理失败，建议保留当前任务编号，再重试后处理或重新提交。");
        }
        if (containsAny(text, "seedance", "volcengine", "ark", "provider", "quota", "轮询超时",
                "繁忙", "http 5", "third", "tts", "vidu", "api request failed")) {
            return new FailureClassification("third_party", "第三方问题",
                    "AI 服务可能暂时繁忙、超时或额度受限，建议稍后用同一批次和样本号重试。");
        }
        if (TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(type) || TaskTypeCode.QUICK_RENDER.equals(type)) {
            return new FailureClassification("system", "系统问题",
                    "汽车销售一键成片链路异常，请保留任务编号、traceId、批次号和样本号供开发定位。");
        }
        return new FailureClassification("system", "系统问题",
                "系统已记录异常诊断信息，请保留任务编号和 traceId 供开发排查。");
    }

    private Map<String, Object> failureInputSummary(TaskEntity entity) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        if (entity == null || !StringUtils.hasText(entity.getInputJson())) {
            return summary;
        }
        try {
            Map<String, Object> input = objectMapper.readValue(entity.getInputJson(), new TypeReference<>() {
            });
            copyIfPresent(input, summary, "testBatch");
            copyIfPresent(input, summary, "sampleId");
            copyIfPresent(input, summary, "outputPurpose");
            copyIfPresent(input, summary, "projectId");
            copyIfPresent(input, summary, "model");
            copyIfPresent(input, summary, "aspectRatio");
            copyIfPresent(input, summary, "segmentCount");
            copyIfPresent(input, summary, "segmentDuration");
            Object assetIds = input.get("assetIds");
            if (assetIds instanceof List<?> list) {
                summary.put("assetCount", list.size());
                summary.put("assetIds", list);
            }
            Object carImageUrls = input.get("carImageUrls");
            if (carImageUrls instanceof List<?> list) {
                summary.put("carImageCount", list.size());
            }
        } catch (Exception ignored) {
            summary.put("inputJsonReadable", false);
        }
        return summary;
    }

    private void copyIfPresent(Map<String, Object> input, Map<String, Object> output, String key) {
        if (input != null && input.containsKey(key)) {
            output.put(key, input.get(key));
        }
    }

    private boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text) || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && text.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String writeJsonOrFallback(Map<String, Object> value, String originalOutputJson) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            Map<String, Object> fallback = new java.util.LinkedHashMap<>();
            if (StringUtils.hasText(originalOutputJson)) {
                fallback.put("rawOutput", originalOutputJson);
            }
            fallback.put("failureDiagnostics", value == null ? Map.of() : value.get("failureDiagnostics"));
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ignoredAgain) {
                return originalOutputJson;
            }
        }
    }

    private record FailureClassification(String category, String reason, String userAdvice) {
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
