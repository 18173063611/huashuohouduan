package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.dto.AdminTaskManualRetryApplyRequest;
import com.huashuo.admin.dto.AdminTaskManualRetryRequest;
import com.huashuo.admin.dto.AdminTaskProviderOpsUpdateRequest;
import com.huashuo.admin.entity.ProviderOpsTicketActionEntity;
import com.huashuo.admin.entity.ProviderOpsTicketEntity;
import com.huashuo.admin.mapper.ProviderOpsTicketActionMapper;
import com.huashuo.admin.mapper.ProviderOpsTicketMapper;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.service.AdminProviderOpsTicketService;
import com.huashuo.admin.service.AdminTaskAdminService;
import com.huashuo.admin.vo.AdminProviderOpsActionItem;
import com.huashuo.admin.vo.AdminProviderOpsTicketItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class AdminProviderOpsTicketServiceImpl implements AdminProviderOpsTicketService {

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
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> RETRY_APPROVAL_STATUSES = Set.of(
            "NONE",
            "PENDING",
            "APPROVED",
            "REJECTED",
            "DISPATCHED"
    );
    private static final Set<String> CLOSED_STATUSES = Set.of("RESOLVED", "IGNORED", "RETRY_DISPATCHED");

    private final ProviderOpsTicketMapper ticketMapper;
    private final ProviderOpsTicketActionMapper actionMapper;
    private final TaskMapper taskMapper;
    private final AdminTaskAdminService adminTaskAdminService;

    public AdminProviderOpsTicketServiceImpl(ProviderOpsTicketMapper ticketMapper,
                                             ProviderOpsTicketActionMapper actionMapper,
                                             TaskMapper taskMapper,
                                             AdminTaskAdminService adminTaskAdminService) {
        this.ticketMapper = ticketMapper;
        this.actionMapper = actionMapper;
        this.taskMapper = taskMapper;
        this.adminTaskAdminService = adminTaskAdminService;
    }

    @Override
    public PageResult<AdminProviderOpsTicketItem> listTickets(String status,
                                                              String priority,
                                                              Long assigneeAdminId,
                                                              Boolean overdueOnly,
                                                              String supplierTicketId,
                                                              String providerTaskId,
                                                              String taskType,
                                                              String retryApprovalStatus,
                                                              Integer pageNo,
                                                              Integer pageSize) {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<ProviderOpsTicketEntity> wrapper = new LambdaQueryWrapper<>();

        String normalizedStatus = normalize(status, PROVIDER_OPS_STATUSES, "provider ops status");
        if (normalizedStatus != null) {
            wrapper.eq(ProviderOpsTicketEntity::getStatus, normalizedStatus);
        }
        String normalizedPriority = normalize(priority, PRIORITIES, "provider ops priority");
        if (normalizedPriority != null) {
            wrapper.eq(ProviderOpsTicketEntity::getPriority, normalizedPriority);
        }
        if (assigneeAdminId != null) {
            wrapper.eq(ProviderOpsTicketEntity::getAssigneeAdminId, assigneeAdminId);
        }
        if (StringUtils.hasText(supplierTicketId)) {
            wrapper.like(ProviderOpsTicketEntity::getSupplierTicketId, supplierTicketId.trim());
        }
        if (StringUtils.hasText(providerTaskId)) {
            wrapper.like(ProviderOpsTicketEntity::getProviderTaskId, providerTaskId.trim());
        }
        if (StringUtils.hasText(taskType)) {
            wrapper.eq(ProviderOpsTicketEntity::getTaskType, taskType.trim().toUpperCase());
        }
        String normalizedRetry = normalize(retryApprovalStatus, RETRY_APPROVAL_STATUSES, "retry approval status");
        if (normalizedRetry != null) {
            wrapper.eq(ProviderOpsTicketEntity::getRetryApprovalStatus, normalizedRetry);
        }
        if (Boolean.TRUE.equals(overdueOnly)) {
            wrapper.isNotNull(ProviderOpsTicketEntity::getSlaDeadlineAt)
                    .lt(ProviderOpsTicketEntity::getSlaDeadlineAt, LocalDateTime.now())
                    .isNull(ProviderOpsTicketEntity::getClosedAt)
                    .notIn(ProviderOpsTicketEntity::getStatus, CLOSED_STATUSES);
        }

        long total = ticketMapper.selectCount(wrapper);
        wrapper.orderByAsc(ProviderOpsTicketEntity::getSlaDeadlineAt)
                .orderByDesc(ProviderOpsTicketEntity::getUpdatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminProviderOpsTicketItem> records = ticketMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    @Transactional
    public AdminProviderOpsTicketItem updateTicket(Long ticketId,
                                                   AdminTaskProviderOpsUpdateRequest request,
                                                   AdminOperationContext context) {
        ProviderOpsTicketEntity ticket = requireTicket(ticketId);
        adminTaskAdminService.updateProviderOps(ticket.getTaskId(), request, context);
        return requireTicketItem(ticketId);
    }

    @Override
    @Transactional
    public AdminProviderOpsTicketItem requestManualRetry(Long ticketId,
                                                         AdminTaskManualRetryApplyRequest request,
                                                         AdminOperationContext context) {
        ProviderOpsTicketEntity ticket = requireTicket(ticketId);
        adminTaskAdminService.requestManualRetry(ticket.getTaskId(), request, context);
        return requireTicketItem(ticketId);
    }

    @Override
    @Transactional
    public AdminProviderOpsTicketItem reviewManualRetry(Long ticketId,
                                                        AdminTaskManualRetryRequest request,
                                                        AdminOperationContext context) {
        ProviderOpsTicketEntity ticket = requireTicket(ticketId);
        adminTaskAdminService.manualRetry(ticket.getTaskId(), request, context);
        return requireTicketItem(ticketId);
    }

    private AdminProviderOpsTicketItem requireTicketItem(Long ticketId) {
        return toItem(requireTicket(ticketId));
    }

    private ProviderOpsTicketEntity requireTicket(Long ticketId) {
        if (ticketId == null || ticketId <= 0) {
            throw new BusinessException(40000, "Provider ops ticket id is required");
        }
        ProviderOpsTicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException(40400, "Provider ops ticket not found");
        }
        return ticket;
    }

    private AdminProviderOpsTicketItem toItem(ProviderOpsTicketEntity ticket) {
        TaskEntity task = ticket.getTaskId() == null ? null : taskMapper.selectById(ticket.getTaskId());
        List<AdminProviderOpsActionItem> actions = actionMapper.selectList(new LambdaQueryWrapper<ProviderOpsTicketActionEntity>()
                        .eq(ProviderOpsTicketActionEntity::getTicketId, ticket.getTicketId())
                        .orderByDesc(ProviderOpsTicketActionEntity::getCreatedAt)
                        .last("limit 20"))
                .stream()
                .map(this::toActionItem)
                .toList();
        String taskStatus = task == null ? null : task.getStatus();
        boolean manualRetryRequired = "running".equalsIgnoreCase(ticket.getProviderStatus())
                || "manual_provider_escalation".equalsIgnoreCase(ticket.getNextAction());
        boolean canManualRetry = taskStatus != null && !"QUEUED".equals(taskStatus) && !"RUNNING".equals(taskStatus);
        return new AdminProviderOpsTicketItem(
                ticket.getTicketId(),
                ticket.getTaskId(),
                ticket.getOwnerUserId(),
                ticket.getTaskType(),
                taskStatus,
                firstText(ticket.getProvider(), task == null ? null : task.getProvider()),
                task == null ? null : task.getModelCode(),
                task == null ? null : task.getErrorMessage(),
                ticket.getProviderTaskId(),
                firstText(ticket.getProviderStatus(), "unknown"),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getAssigneeAdminId(),
                ticket.getSupplierTicketId(),
                ticket.getCanDeleteProviderTask() != null && ticket.getCanDeleteProviderTask() != 0,
                ticket.getNextAction(),
                ticket.getAlertLevel(),
                ticket.getAlertReason(),
                ticket.getAlertElapsedSeconds(),
                ticket.getAlertTimeoutSeconds(),
                ticket.getSlaDeadlineAt(),
                isSlaOverdue(ticket),
                ticket.getSupplierResponse(),
                ticket.getAttachmentJson(),
                ticket.getLastRemark(),
                ticket.getRetryApprovalStatus(),
                ticket.getRetryRequestedByAdminId(),
                ticket.getRetryRequestedAt(),
                ticket.getRetryApprovedByAdminId(),
                ticket.getRetryApprovedAt(),
                ticket.getRetryApprovalRemark(),
                manualRetryRequired,
                canManualRetry,
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getClosedAt(),
                task == null ? null : task.getCreatedAt(),
                task == null ? null : task.getUpdatedAt(),
                actions
        );
    }

    private boolean isSlaOverdue(ProviderOpsTicketEntity ticket) {
        return ticket.getSlaDeadlineAt() != null
                && ticket.getSlaDeadlineAt().isBefore(LocalDateTime.now())
                && ticket.getClosedAt() == null
                && !CLOSED_STATUSES.contains(String.valueOf(ticket.getStatus()).toUpperCase());
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

    private String normalize(String value, Set<String> allowed, String label) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(40000, "Unsupported " + label + ": " + value);
        }
        return normalized;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
