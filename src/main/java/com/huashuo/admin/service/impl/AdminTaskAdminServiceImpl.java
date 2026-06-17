package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.admin.dto.AdminTaskManualRetryApplyRequest;
import com.huashuo.admin.dto.AdminTaskManualRetryRequest;
import com.huashuo.admin.dto.AdminTaskProviderOpsUpdateRequest;
import com.huashuo.admin.entity.ProviderOpsTicketActionEntity;
import com.huashuo.admin.entity.ProviderOpsTicketEntity;
import com.huashuo.admin.mapper.ProviderOpsTicketActionMapper;
import com.huashuo.admin.mapper.ProviderOpsTicketMapper;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.service.AdminTaskAdminService;
import com.huashuo.admin.vo.AdminProviderOpsActionItem;
import com.huashuo.admin.vo.AdminTaskItem;
import com.huashuo.admin.vo.AdminTaskProviderOps;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.job.TaskRetryDispatcher;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

@Service
public class AdminTaskAdminServiceImpl implements AdminTaskAdminService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> PROVIDER_OPS_STATUSES = Set.of(
            "OPEN",
            "ESCALATED",
            "SUPPLIER_PROCESSING",
            "RESOLVED",
            "IGNORED",
            "RETRY_PENDING",
            "RETRY_DISPATCHED"
    );
    private static final Set<String> PROVIDER_OPS_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");

    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final TaskRetryDispatcher taskRetryDispatcher;
    private final AdminOperationAuditService auditService;
    private final ProviderOpsTicketMapper ticketMapper;
    private final ProviderOpsTicketActionMapper actionMapper;

    public AdminTaskAdminServiceImpl(TaskMapper taskMapper,
                                     ObjectMapper objectMapper,
                                     TaskService taskService,
                                     TaskRetryDispatcher taskRetryDispatcher,
                                     AdminOperationAuditService auditService,
                                     ProviderOpsTicketMapper ticketMapper,
                                     ProviderOpsTicketActionMapper actionMapper) {
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.taskRetryDispatcher = taskRetryDispatcher;
        this.auditService = auditService;
        this.ticketMapper = ticketMapper;
        this.actionMapper = actionMapper;
    }

    @Override
    public PageResult<AdminTaskItem> listTasks(Long ownerUserId, String taskType, String status, String modelCode,
                                               Boolean providerOpsOnly, String providerOpsStatus,
                                               Integer pageNo, Integer pageSize) {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        if (ownerUserId != null) {
            wrapper.eq(TaskEntity::getOwnerUserId, ownerUserId);
        }
        if (StringUtils.hasText(taskType)) {
            wrapper.eq(TaskEntity::getTaskType, taskType.trim().toUpperCase());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(TaskEntity::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(modelCode)) {
            wrapper.eq(TaskEntity::getModelCode, modelCode.trim());
        }
        if (Boolean.TRUE.equals(providerOpsOnly)) {
            wrapper.and(q -> q
                    .inSql(TaskEntity::getTaskId, "select task_id from provider_ops_ticket where deleted = 0")
                    .or()
                    .apply("output_json is not null and json_valid(output_json) = 1 and "
                            + "(json_extract(output_json, '$.providerOpsAlert.providerTaskId') is not null "
                            + "or json_extract(output_json, '$.activeProviderTaskId') is not null)")
            );
        }
        String normalizedProviderOpsStatus = normalizeProviderOpsStatus(providerOpsStatus, false);
        if (normalizedProviderOpsStatus != null) {
            wrapper.and(q -> q
                    .inSql(TaskEntity::getTaskId, "select task_id from provider_ops_ticket where deleted = 0 and status = '"
                            + normalizedProviderOpsStatus + "'")
                    .or()
                    .apply("output_json is not null and json_valid(output_json) = 1 and "
                                    + "json_unquote(json_extract(output_json, '$.providerOpsResolution.status')) = {0}",
                            normalizedProviderOpsStatus)
            );
        }
        long total = taskMapper.selectCount(wrapper);
        wrapper.orderByDesc(TaskEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminTaskItem> records = taskMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    @Transactional
    public AdminTaskItem updateProviderOps(Long taskId, AdminTaskProviderOpsUpdateRequest request,
                                           AdminOperationContext context) {
        TaskEntity entity = requireTask(taskId);
        ProviderOpsTicketEntity ticket = ensureTicket(entity, context);
        Map<String, Object> before = ticketSnapshot(ticket);
        String fromStatus = ticket.getStatus();
        applyTicketUpdate(ticket, request, context, "UPDATE");
        ticketMapper.updateById(ticket);
        syncNullableTicketFields(ticket);
        insertAction(ticket, "UPDATE", fromStatus, ticket.getStatus(), context,
                ticket.getLastRemark(), ticket.getSupplierResponse(), ticket.getAttachmentJson());
        auditService.record(context, "TASK_PROVIDER_OPS_UPDATE", "PROVIDER_OPS_TICKET", ticket.getTicketId(),
                before, ticketSnapshot(ticket));
        return toItem(requireTask(taskId));
    }

    @Override
    @Transactional
    public AdminTaskItem requestManualRetry(Long taskId, AdminTaskManualRetryApplyRequest request,
                                            AdminOperationContext context) {
        TaskEntity entity = requireTask(taskId);
        assertRetryCandidate(entity);
        ProviderOpsTicketEntity ticket = ensureTicket(entity, context);
        String remark = trimToNull(request.remark());
        if (!StringUtils.hasText(remark)) {
            throw new BusinessException(40000, "Manual retry request remark is required");
        }
        Map<String, Object> before = ticketSnapshot(ticket);
        String fromStatus = ticket.getStatus();
        LocalDateTime now = LocalDateTime.now();
        ticket.setStatus("RETRY_PENDING");
        ticket.setSupplierTicketId(trimToNull(request.supplierTicketId()));
        ticket.setRetryApprovalStatus("PENDING");
        ticket.setRetryRequestedByAdminId(adminUserId(context));
        ticket.setRetryRequestedAt(now);
        ticket.setRetryApprovalRemark(remark);
        ticket.setLastRemark(remark);
        ticket.setClosedAt(null);
        ticket.setUpdatedAt(now);
        ticketMapper.updateById(ticket);
        insertAction(ticket, "RETRY_REQUEST", fromStatus, ticket.getStatus(), context,
                remark, ticket.getSupplierResponse(), ticket.getAttachmentJson());
        auditService.record(context, "TASK_PROVIDER_OPS_RETRY_REQUEST", "PROVIDER_OPS_TICKET", ticket.getTicketId(),
                before, ticketSnapshot(ticket));
        return toItem(requireTask(taskId));
    }

    @Override
    @Transactional
    public AdminTaskItem manualRetry(Long taskId, AdminTaskManualRetryRequest request, AdminOperationContext context) {
        TaskEntity entity = requireTask(taskId);
        assertRetryCandidate(entity);
        ProviderOpsTicketEntity ticket = latestTicket(taskId);
        if (ticket == null || !"PENDING".equals(ticket.getRetryApprovalStatus())) {
            throw new BusinessException(40900, "Manual retry requires a pending approval request");
        }
        String remark = trimToNull(request.remark());
        if (!StringUtils.hasText(remark)) {
            throw new BusinessException(40000, "Manual retry approval remark is required");
        }
        boolean approved = Boolean.TRUE.equals(request.approved());
        if (approved && !Boolean.TRUE.equals(request.confirmProviderResolved())) {
            throw new BusinessException(40000, "Approved retry requires provider-side confirmation");
        }
        Map<String, Object> before = ticketSnapshot(ticket);
        String fromStatus = ticket.getStatus();
        LocalDateTime now = LocalDateTime.now();
        ticket.setSupplierTicketId(firstText(request.supplierTicketId(), ticket.getSupplierTicketId()));
        ticket.setRetryApprovedByAdminId(adminUserId(context));
        ticket.setRetryApprovedAt(now);
        ticket.setRetryApprovalRemark(remark);
        ticket.setLastRemark(remark);
        ticket.setUpdatedAt(now);
        if (!approved) {
            ticket.setRetryApprovalStatus("REJECTED");
            ticket.setStatus("ESCALATED");
            ticket.setClosedAt(null);
            ticketMapper.updateById(ticket);
            insertAction(ticket, "RETRY_REJECT", fromStatus, ticket.getStatus(), context,
                    remark, ticket.getSupplierResponse(), ticket.getAttachmentJson());
            auditService.record(context, "TASK_PROVIDER_OPS_RETRY_REJECT", "PROVIDER_OPS_TICKET", ticket.getTicketId(),
                    before, ticketSnapshot(ticket));
            return toItem(requireTask(taskId));
        }

        ticket.setRetryApprovalStatus("APPROVED");
        ticket.setStatus("RETRY_DISPATCHED");
        ticket.setClosedAt(now);
        ticketMapper.updateById(ticket);
        insertAction(ticket, "RETRY_APPROVE", fromStatus, ticket.getStatus(), context,
                remark, ticket.getSupplierResponse(), ticket.getAttachmentJson());
        TaskItem retried = taskService.retryTask(entity.getTaskId(), viewerForTask(entity));
        taskRetryDispatcher.dispatch(retried);
        ticket.setRetryApprovalStatus("DISPATCHED");
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        insertAction(ticket, "RETRY_DISPATCH", "RETRY_DISPATCHED", "RETRY_DISPATCHED", context,
                "Retry dispatched to task queue", ticket.getSupplierResponse(), ticket.getAttachmentJson());
        auditService.record(context, "TASK_PROVIDER_OPS_RETRY_APPROVE", "PROVIDER_OPS_TICKET", ticket.getTicketId(),
                before, ticketSnapshot(ticket));
        auditService.record(context, "TASK_ADMIN_RETRY", "TASK", entity.getTaskId(),
                Map.of("status", entity.getStatus(), "retryCount", entity.getRetryCount()),
                Map.of("status", retried.status(), "retryCount", retried.retryCount()));
        return toItem(requireTask(taskId));
    }

    private AdminTaskItem toItem(TaskEntity entity) {
        return new AdminTaskItem(
                entity.getTaskId(),
                entity.getOwnerUserId(),
                entity.getTaskType(),
                entity.getStatus(),
                entity.getProgress(),
                entity.getModelCode(),
                entity.getProvider(),
                entity.getUsageUnit(),
                entity.getEstimatedUsage(),
                entity.getActualUsage(),
                entity.getEstimatedCreditCost(),
                entity.getActualCreditCost(),
                entity.getSettlementStatus(),
                entity.getCreditCost(),
                entity.getCreditLogId(),
                entity.getQueueName(),
                entity.getMessageId(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                providerOps(entity),
                entity.getTraceId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private AdminTaskProviderOps providerOps(TaskEntity entity) {
        ProviderOpsTicketEntity ticket = latestTicket(entity.getTaskId());
        if (ticket != null) {
            return ticketProviderOps(entity, ticket);
        }
        return legacyProviderOps(entity);
    }

    private AdminTaskProviderOps ticketProviderOps(TaskEntity entity, ProviderOpsTicketEntity ticket) {
        List<AdminProviderOpsActionItem> actions = actionMapper.selectList(new LambdaQueryWrapper<ProviderOpsTicketActionEntity>()
                        .eq(ProviderOpsTicketActionEntity::getTicketId, ticket.getTicketId())
                        .orderByDesc(ProviderOpsTicketActionEntity::getCreatedAt)
                        .last("limit 20"))
                .stream()
                .map(this::toActionItem)
                .toList();
        boolean manualRetryRequired = "running".equalsIgnoreCase(ticket.getProviderStatus())
                || "manual_provider_escalation".equalsIgnoreCase(ticket.getNextAction());
        boolean canManualRetry = !"QUEUED".equals(entity.getStatus()) && !"RUNNING".equals(entity.getStatus());
        return new AdminTaskProviderOps(
                ticket.getTicketId(),
                ticket.getProviderTaskId(),
                firstText(ticket.getProviderStatus(), "unknown"),
                ticket.getAlertElapsedSeconds(),
                ticket.getAlertTimeoutSeconds(),
                ticket.getCanDeleteProviderTask() != null && ticket.getCanDeleteProviderTask() != 0,
                ticket.getNextAction(),
                ticket.getAlertLevel(),
                ticket.getAlertReason(),
                null,
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getAssigneeAdminId(),
                ticket.getSupplierTicketId(),
                ticket.getLastRemark(),
                ticket.getSupplierResponse(),
                ticket.getAttachmentJson(),
                null,
                ticket.getUpdatedAt() == null ? null : ticket.getUpdatedAt().toString(),
                ticket.getSlaDeadlineAt(),
                ticket.getRetryApprovalStatus(),
                ticket.getRetryRequestedByAdminId(),
                ticket.getRetryRequestedAt(),
                ticket.getRetryApprovedByAdminId(),
                ticket.getRetryApprovedAt(),
                ticket.getRetryApprovalRemark(),
                manualRetryRequired,
                canManualRetry,
                actions
        );
    }

    private AdminTaskProviderOps legacyProviderOps(TaskEntity entity) {
        Map<String, Object> output = readOutputJson(entity);
        Map<String, Object> alert = mapValue(output.get("providerOpsAlert"));
        Map<String, Object> resolution = mapValue(output.get("providerOpsResolution"));
        String providerTaskId = firstText(
                stringValue(alert.get("providerTaskId")),
                stringValue(output.get("activeProviderTaskId")),
                providerTaskIdFromError(entity.getErrorMessage())
        );
        if (!StringUtils.hasText(providerTaskId) && resolution.isEmpty()) {
            return null;
        }
        String providerStatus = firstText(
                stringValue(alert.get("providerStatus")),
                stringValue(output.get("activeProviderStatus")),
                providerStatusFromError(entity.getErrorMessage()),
                "unknown"
        );
        Boolean canDelete = booleanValue(alert.get("canDeleteProviderTask"));
        if (canDelete == null) {
            canDelete = "queued".equalsIgnoreCase(providerStatus);
        }
        boolean manualRetryRequired = "running".equalsIgnoreCase(providerStatus)
                || "manual_provider_escalation".equalsIgnoreCase(stringValue(alert.get("nextAction")));
        boolean canManualRetry = !"QUEUED".equals(entity.getStatus()) && !"RUNNING".equals(entity.getStatus());
        return new AdminTaskProviderOps(
                null,
                providerTaskId,
                providerStatus,
                longValue(firstNonNull(alert.get("elapsedSeconds"), output.get("activeSegmentElapsedSeconds"))),
                longValue(firstNonNull(alert.get("timeoutSeconds"), output.get("activeSegmentTimeoutSeconds"))),
                canDelete,
                stringValue(alert.get("nextAction")),
                stringValue(alert.get("level")),
                stringValue(alert.get("reason")),
                stringValue(alert.get("alertedAt")),
                firstText(stringValue(resolution.get("status")), "OPEN"),
                "NORMAL",
                longValue(resolution.get("assigneeAdminId")),
                stringValue(resolution.get("supplierTicketId")),
                stringValue(resolution.get("remark")),
                stringValue(resolution.get("supplierResponse")),
                stringValue(resolution.get("attachmentJson")),
                longValue(resolution.get("operatorAdminId")),
                stringValue(resolution.get("updatedAt")),
                null,
                "NONE",
                null,
                null,
                null,
                null,
                null,
                manualRetryRequired,
                canManualRetry,
                List.of()
        );
    }

    private ProviderOpsTicketEntity ensureTicket(TaskEntity entity, AdminOperationContext context) {
        ProviderOpsTicketEntity existing = latestTicket(entity.getTaskId());
        if (existing != null) {
            return existing;
        }
        AdminTaskProviderOps legacy = legacyProviderOps(entity);
        if (legacy == null || !StringUtils.hasText(legacy.providerTaskId())) {
            throw new BusinessException(40900, "Task has no provider diagnostics to create ops ticket");
        }
        LocalDateTime now = LocalDateTime.now();
        ProviderOpsTicketEntity ticket = new ProviderOpsTicketEntity();
        ticket.setTaskId(entity.getTaskId());
        ticket.setOwnerUserId(entity.getOwnerUserId());
        ticket.setTaskType(entity.getTaskType());
        ticket.setProvider(entity.getProvider());
        ticket.setProviderTaskId(legacy.providerTaskId());
        ticket.setProviderStatus(legacy.providerStatus());
        ticket.setStatus(firstText(legacy.opsStatus(), "OPEN"));
        ticket.setPriority(firstText(legacy.priority(), "NORMAL"));
        ticket.setSupplierTicketId(legacy.supplierTicketId());
        ticket.setCanDeleteProviderTask(Boolean.TRUE.equals(legacy.canDeleteProviderTask()) ? 1 : 0);
        ticket.setNextAction(legacy.nextAction());
        ticket.setAlertLevel(legacy.alertLevel());
        ticket.setAlertReason(legacy.alertReason());
        ticket.setAlertElapsedSeconds(legacy.elapsedSeconds());
        ticket.setAlertTimeoutSeconds(legacy.timeoutSeconds());
        ticket.setRetryApprovalStatus("NONE");
        ticket.setLastRemark(legacy.remark());
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticket.setDeleted(0);
        ticketMapper.insert(ticket);
        insertAction(ticket, "CREATE", null, ticket.getStatus(), context,
                "Created from provider diagnostics", legacy.supplierResponse(), legacy.attachmentJson());
        return ticket;
    }

    private void applyTicketUpdate(ProviderOpsTicketEntity ticket,
                                   AdminTaskProviderOpsUpdateRequest request,
                                   AdminOperationContext context,
                                   String action) {
        String normalizedStatus = normalizeProviderOpsStatus(request.opsStatus(), true);
        String normalizedPriority = normalizePriority(request.priority());
        LocalDateTime now = LocalDateTime.now();
        ticket.setStatus(normalizedStatus);
        ticket.setPriority(normalizedPriority);
        ticket.setAssigneeAdminId(request.assigneeAdminId());
        ticket.setSlaDeadlineAt(request.slaDeadlineAt());
        ticket.setSupplierTicketId(trimToNull(request.supplierTicketId()));
        ticket.setLastRemark(trimToNull(request.remark()));
        ticket.setSupplierResponse(trimToNull(request.supplierResponse()));
        ticket.setAttachmentJson(trimToNull(request.attachmentJson()));
        ticket.setUpdatedAt(now);
        ticket.setClosedAt("RESOLVED".equals(normalizedStatus) || "IGNORED".equals(normalizedStatus) ? now : null);
        if ("sla_overdue".equalsIgnoreCase(ticket.getAlertReason())
                && (ticket.getSlaDeadlineAt() == null
                || ticket.getSlaDeadlineAt().isAfter(now)
                || "RESOLVED".equals(normalizedStatus)
                || "IGNORED".equals(normalizedStatus))) {
            ticket.setAlertLevel(null);
            ticket.setAlertReason(null);
        }
        if ("UPDATE".equals(action) && !"RETRY_PENDING".equals(normalizedStatus)) {
            ticket.setRetryApprovalStatus(firstText(ticket.getRetryApprovalStatus(), "NONE"));
        }
    }

    private void insertAction(ProviderOpsTicketEntity ticket,
                              String actionType,
                              String fromStatus,
                              String toStatus,
                              AdminOperationContext context,
                              String remark,
                              String supplierResponse,
                              String attachmentJson) {
        ProviderOpsTicketActionEntity action = new ProviderOpsTicketActionEntity();
        action.setTicketId(ticket.getTicketId());
        action.setTaskId(ticket.getTaskId());
        action.setActionType(actionType);
        action.setFromStatus(fromStatus);
        action.setToStatus(toStatus);
        action.setOperatorAdminId(adminUserId(context));
        action.setSupplierTicketId(ticket.getSupplierTicketId());
        action.setRemark(trimToNull(remark));
        action.setSupplierResponse(trimToNull(supplierResponse));
        action.setAttachmentJson(trimToNull(attachmentJson));
        action.setRetryApprovalStatus(ticket.getRetryApprovalStatus());
        action.setCreatedAt(LocalDateTime.now());
        action.setDeleted(0);
        actionMapper.insert(action);
    }

    private void syncNullableTicketFields(ProviderOpsTicketEntity ticket) {
        LambdaUpdateWrapper<ProviderOpsTicketEntity> update = new LambdaUpdateWrapper<ProviderOpsTicketEntity>()
                .eq(ProviderOpsTicketEntity::getTicketId, ticket.getTicketId())
                .set(ProviderOpsTicketEntity::getAssigneeAdminId, ticket.getAssigneeAdminId())
                .set(ProviderOpsTicketEntity::getSupplierTicketId, ticket.getSupplierTicketId())
                .set(ProviderOpsTicketEntity::getSlaDeadlineAt, ticket.getSlaDeadlineAt())
                .set(ProviderOpsTicketEntity::getSupplierResponse, ticket.getSupplierResponse())
                .set(ProviderOpsTicketEntity::getAttachmentJson, ticket.getAttachmentJson())
                .set(ProviderOpsTicketEntity::getLastRemark, ticket.getLastRemark())
                .set(ProviderOpsTicketEntity::getClosedAt, ticket.getClosedAt())
                .set(ProviderOpsTicketEntity::getAlertLevel, ticket.getAlertLevel())
                .set(ProviderOpsTicketEntity::getAlertReason, ticket.getAlertReason());
        ticketMapper.update(null, update);
    }

    private AdminProviderOpsActionItem toActionItem(ProviderOpsTicketActionEntity action) {
        return new AdminProviderOpsActionItem(
                action.getActionId(),
                action.getTicketId(),
                action.getTaskId(),
                action.getActionType(),
                action.getFromStatus(),
                action.getToStatus(),
                action.getOperatorAdminId(),
                action.getSupplierTicketId(),
                action.getRemark(),
                action.getSupplierResponse(),
                action.getAttachmentJson(),
                action.getRetryApprovalStatus(),
                action.getCreatedAt()
        );
    }

    private ProviderOpsTicketEntity latestTicket(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return ticketMapper.selectOne(new LambdaQueryWrapper<ProviderOpsTicketEntity>()
                .eq(ProviderOpsTicketEntity::getTaskId, taskId)
                .orderByDesc(ProviderOpsTicketEntity::getUpdatedAt)
                .last("limit 1"));
    }

    private TaskEntity requireTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new BusinessException(40000, "Task id is required");
        }
        TaskEntity entity = taskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(40400, "Task not found");
        }
        return entity;
    }

    private void assertRetryCandidate(TaskEntity entity) {
        if (!"FAILED".equals(entity.getStatus())
                && !"RETRYABLE".equals(entity.getStatus())
                && !"CANCELED".equals(entity.getStatus())) {
            throw new BusinessException(40900, "Only failed, retryable or canceled tasks can be manually retried");
        }
    }

    private OptionalLong viewerForTask(TaskEntity entity) {
        return entity.getOwnerUserId() == null ? OptionalLong.empty() : OptionalLong.of(entity.getOwnerUserId());
    }

    private Map<String, Object> readOutputJson(TaskEntity entity) {
        String outputJson = entity == null ? null : entity.getOutputJson();
        if (!StringUtils.hasText(outputJson)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(outputJson, new TypeReference<>() {
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

    private Map<String, Object> ticketSnapshot(ProviderOpsTicketEntity ticket) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ticketId", ticket.getTicketId());
        snapshot.put("taskId", ticket.getTaskId());
        snapshot.put("providerTaskId", ticket.getProviderTaskId());
        snapshot.put("providerStatus", ticket.getProviderStatus());
        snapshot.put("status", ticket.getStatus());
        snapshot.put("priority", ticket.getPriority());
        snapshot.put("assigneeAdminId", ticket.getAssigneeAdminId());
        snapshot.put("supplierTicketId", ticket.getSupplierTicketId());
        snapshot.put("slaDeadlineAt", ticket.getSlaDeadlineAt());
        snapshot.put("retryApprovalStatus", ticket.getRetryApprovalStatus());
        snapshot.put("lastRemark", ticket.getLastRemark());
        snapshot.put("updatedAt", ticket.getUpdatedAt());
        return snapshot;
    }

    private String normalizeProviderOpsStatus(String status, boolean required) {
        String value = trimToNull(status);
        if (value == null) {
            if (required) {
                throw new BusinessException(40000, "Provider ops status is required");
            }
            return null;
        }
        String normalized = value.toUpperCase();
        if (!PROVIDER_OPS_STATUSES.contains(normalized)) {
            throw new BusinessException(40000, "Unsupported provider ops status: " + value);
        }
        return normalized;
    }

    private String normalizePriority(String priority) {
        String value = firstText(priority, "NORMAL").toUpperCase();
        if (!PROVIDER_OPS_PRIORITIES.contains(value)) {
            throw new BusinessException(40000, "Unsupported provider ops priority: " + priority);
        }
        return value;
    }

    private String providerTaskIdFromError(String errorMessage) {
        String text = trimToNull(errorMessage);
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher provider = java.util.regex.Pattern.compile("providerTaskId=([^\\s]+)").matcher(text);
        if (provider.find()) {
            return provider.group(1);
        }
        java.util.regex.Matcher task = java.util.regex.Pattern.compile("taskId=(cgt-[^\\s]+)").matcher(text);
        return task.find() ? task.group(1) : null;
    }

    private String providerStatusFromError(String errorMessage) {
        String text = trimToNull(errorMessage);
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher lastStatus = java.util.regex.Pattern.compile("lastStatus=([^\\s]+)").matcher(text);
        if (lastStatus.find()) {
            return lastStatus.group(1);
        }
        java.util.regex.Matcher cnStatus = java.util.regex.Pattern.compile("最近状态=([^\\s]+)").matcher(text);
        return cnStatus.find() ? cnStatus.group(1) : null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String text = trimToNull(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Long adminUserId(AdminOperationContext context) {
        return context == null ? null : context.adminUserId();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }
}
