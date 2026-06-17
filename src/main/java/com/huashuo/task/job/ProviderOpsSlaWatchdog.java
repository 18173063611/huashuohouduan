package com.huashuo.task.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.entity.ProviderOpsTicketActionEntity;
import com.huashuo.admin.entity.ProviderOpsTicketEntity;
import com.huashuo.admin.mapper.ProviderOpsTicketActionMapper;
import com.huashuo.admin.mapper.ProviderOpsTicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "huashuo.ai-task.provider-ops-sla", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ProviderOpsSlaWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ProviderOpsSlaWatchdog.class);
    private static final Set<String> CLOSED_STATUSES = Set.of("RESOLVED", "IGNORED", "RETRY_DISPATCHED");

    private final ProviderOpsTicketMapper ticketMapper;
    private final ProviderOpsTicketActionMapper actionMapper;
    private final int maxItems;

    public ProviderOpsSlaWatchdog(ProviderOpsTicketMapper ticketMapper,
                                  ProviderOpsTicketActionMapper actionMapper,
                                  @Value("${huashuo.ai-task.provider-ops-sla.max-items:80}") int maxItems) {
        this.ticketMapper = ticketMapper;
        this.actionMapper = actionMapper;
        this.maxItems = Math.max(1, maxItems);
    }

    @Scheduled(
            initialDelayString = "${huashuo.ai-task.provider-ops-sla.scan-initial-delay-ms:90000}",
            fixedDelayString = "${huashuo.ai-task.provider-ops-sla.scan-interval-ms:300000}"
    )
    public void scanOverdueTickets() {
        LocalDateTime now = LocalDateTime.now();
        List<ProviderOpsTicketEntity> overdueTickets = ticketMapper.selectList(
                new LambdaQueryWrapper<ProviderOpsTicketEntity>()
                        .isNotNull(ProviderOpsTicketEntity::getSlaDeadlineAt)
                        .lt(ProviderOpsTicketEntity::getSlaDeadlineAt, now)
                        .isNull(ProviderOpsTicketEntity::getClosedAt)
                        .notIn(ProviderOpsTicketEntity::getStatus, CLOSED_STATUSES)
                        .orderByAsc(ProviderOpsTicketEntity::getSlaDeadlineAt)
                        .last("limit " + maxItems)
        );
        int alerted = 0;
        for (ProviderOpsTicketEntity ticket : overdueTickets) {
            if (markOverdue(ticket, now)) {
                alerted++;
            }
        }
        if (alerted > 0) {
            log.warn("Provider ops SLA watchdog marked {} overdue ticket(s).", alerted);
        }
    }

    private boolean markOverdue(ProviderOpsTicketEntity ticket, LocalDateTime now) {
        if (ticket.getSlaDeadlineAt() == null || hasFreshOverdueAction(ticket)) {
            return false;
        }
        long overdueSeconds = Math.max(0L, Duration.between(ticket.getSlaDeadlineAt(), now).getSeconds());
        log.warn("Provider ops ticket SLA overdue ticketId={} taskId={} providerTaskId={} status={} assigneeAdminId={} overdueSeconds={}",
                ticket.getTicketId(), ticket.getTaskId(), ticket.getProviderTaskId(), ticket.getStatus(),
                ticket.getAssigneeAdminId(), overdueSeconds);

        ticket.setAlertLevel("critical");
        ticket.setAlertReason("sla_overdue");
        ticket.setUpdatedAt(now);
        ticketMapper.updateById(ticket);

        ProviderOpsTicketActionEntity action = new ProviderOpsTicketActionEntity();
        action.setTicketId(ticket.getTicketId());
        action.setTaskId(ticket.getTaskId());
        action.setActionType("SLA_OVERDUE");
        action.setFromStatus(ticket.getStatus());
        action.setToStatus(ticket.getStatus());
        action.setOperatorAdminId(null);
        action.setSupplierTicketId(ticket.getSupplierTicketId());
        action.setRemark("SLA deadline exceeded by " + overdueSeconds + " seconds.");
        action.setSupplierResponse(ticket.getSupplierResponse());
        action.setAttachmentJson(ticket.getAttachmentJson());
        action.setRetryApprovalStatus(ticket.getRetryApprovalStatus());
        action.setCreatedAt(now);
        action.setDeleted(0);
        actionMapper.insert(action);
        return true;
    }

    private boolean hasFreshOverdueAction(ProviderOpsTicketEntity ticket) {
        Long count = actionMapper.selectCount(new LambdaQueryWrapper<ProviderOpsTicketActionEntity>()
                .eq(ProviderOpsTicketActionEntity::getTicketId, ticket.getTicketId())
                .eq(ProviderOpsTicketActionEntity::getActionType, "SLA_OVERDUE")
                .ge(ProviderOpsTicketActionEntity::getCreatedAt, ticket.getSlaDeadlineAt()));
        return count != null && count > 0;
    }
}
