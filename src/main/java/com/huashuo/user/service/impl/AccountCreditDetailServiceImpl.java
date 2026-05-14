package com.huashuo.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.billing.entity.AiBillingStepConfigEntity;
import com.huashuo.billing.entity.AiUsageLogEntity;
import com.huashuo.billing.entity.CreditDebtLogEntity;
import com.huashuo.billing.mapper.AiUsageLogMapper;
import com.huashuo.billing.mapper.CreditDebtLogMapper;
import com.huashuo.billing.model.CreditDebtStatus;
import com.huashuo.billing.model.SettlementStatus;
import com.huashuo.billing.model.UsagePhase;
import com.huashuo.billing.service.BillingStepConfigService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.mapper.UserCreditLogMapper;
import com.huashuo.user.service.AccountCreditDetailService;
import com.huashuo.user.vo.account.AccountCreditLogRecentRow;
import com.huashuo.user.vo.account.TaskCreditDetailLogLine;
import com.huashuo.user.vo.account.TaskCreditDetailResponse;
import com.huashuo.user.vo.account.TaskCreditStepRow;
import com.huashuo.user.vo.account.TaskCreditUsageSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Service
public class AccountCreditDetailServiceImpl implements AccountCreditDetailService {

    private final UserCreditLogMapper userCreditLogMapper;
    private final AiUsageLogMapper aiUsageLogMapper;
    private final CreditDebtLogMapper creditDebtLogMapper;
    private final BillingStepConfigService billingStepConfigService;
    private final TaskService taskService;

    public AccountCreditDetailServiceImpl(
            UserCreditLogMapper userCreditLogMapper,
            AiUsageLogMapper aiUsageLogMapper,
            CreditDebtLogMapper creditDebtLogMapper,
            BillingStepConfigService billingStepConfigService,
            TaskService taskService) {
        this.userCreditLogMapper = userCreditLogMapper;
        this.aiUsageLogMapper = aiUsageLogMapper;
        this.creditDebtLogMapper = creditDebtLogMapper;
        this.billingStepConfigService = billingStepConfigService;
        this.taskService = taskService;
    }

    @Override
    public List<AccountCreditLogRecentRow> listRecentCreditLogs(long userId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 50);
        LambdaQueryWrapper<UserCreditLogEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserCreditLogEntity::getUserId, userId)
                .eq(UserCreditLogEntity::getDeleted, 0)
                .orderByDesc(UserCreditLogEntity::getCreatedAt)
                .last("LIMIT " + lim);
        List<UserCreditLogEntity> rows = userCreditLogMapper.selectList(w);
        Map<Long, TaskMini> taskMeta = loadTaskMeta(rows);
        List<AccountCreditLogRecentRow> out = new ArrayList<>();
        for (UserCreditLogEntity log : rows) {
            Op op = classifyOperation(log);
            Long tid = log.getRelatedTaskId();
            TaskMini tm = tid == null ? null : taskMeta.get(tid);
            String taskName = tid == null ? "—" : (tm == null ? "任务#" + tid : tm.title());
            String taskType = tid == null || tm == null || !StringUtils.hasText(tm.taskType()) ? "—" : tm.taskType();
            out.add(new AccountCreditLogRecentRow(
                    log.getCreditLogId(),
                    log.getCreatedAt(),
                    taskName,
                    taskType,
                    op.typeCode(),
                    op.label(),
                    safe(log.getChangeAmount()),
                    safe(log.getAfterBalance()),
                    "已完成"
            ));
        }
        return out;
    }

    @Override
    public TaskCreditDetailResponse getTaskCreditDetail(long viewerUserId, long taskId) {
        TaskItem task = taskService.getTaskForViewer(taskId, OptionalLong.of(viewerUserId));

        LambdaQueryWrapper<UserCreditLogEntity> logW = new LambdaQueryWrapper<>();
        logW.eq(UserCreditLogEntity::getRelatedTaskId, taskId)
                .eq(UserCreditLogEntity::getDeleted, 0)
                .orderByAsc(UserCreditLogEntity::getCreatedAt);
        List<UserCreditLogEntity> creditLogs = userCreditLogMapper.selectList(logW);

        LambdaQueryWrapper<AiUsageLogEntity> usageW = new LambdaQueryWrapper<>();
        usageW.eq(AiUsageLogEntity::getTaskId, taskId)
                .eq(AiUsageLogEntity::getDeleted, 0)
                .orderByAsc(AiUsageLogEntity::getUsagePhase)
                .orderByAsc(AiUsageLogEntity::getCreatedAt);
        List<AiUsageLogEntity> usageRows = aiUsageLogMapper.selectList(usageW);

        AiUsageLogEntity actualUsage = usageRows.stream()
                .filter(u -> UsagePhase.ACTUAL.equalsIgnoreCase(trimOrNull(u.getUsagePhase())))
                .findFirst()
                .orElse(null);
        if (actualUsage == null) {
            actualUsage = usageRows.stream()
                    .filter(u -> UsagePhase.ESTIMATE.equalsIgnoreCase(trimOrNull(u.getUsagePhase())))
                    .findFirst()
                    .orElse(null);
        }

        long unpaid = sumOutstandingDebt(taskId);
        long est = task.estimatedCreditCost() != null ? task.estimatedCreditCost() : safe(task.creditCost());
        long act = task.actualCreditCost() != null ? task.actualCreditCost() : 0L;
        long paid = Math.max(0L, act - unpaid);

        List<TaskCreditStepRow> steps = buildStepRows(task.taskType(), est, act);
        List<String> explanation = buildExplanation(task.taskType(), steps, est, act, task.settlementStatus());
        if (unpaid > 0) {
            explanation.add("");
            explanation.add("【存在待补扣积分】本次任务实际消耗超出预估且账户余额不足以全额补扣，当前仍有 "
                    + unpaid + " 积分待补扣。请充值后由系统完成后续结算（自动补扣能力将陆续开放）。");
        }
        List<TaskCreditDetailLogLine> logLines = new ArrayList<>();
        for (UserCreditLogEntity log : creditLogs) {
            Op op = classifyOperation(log);
            logLines.add(new TaskCreditDetailLogLine(
                    log.getCreatedAt(),
                    log.getChangeType(),
                    op.typeCode(),
                    op.label(),
                    safe(log.getChangeAmount()),
                    safe(log.getBeforeBalance()),
                    safe(log.getAfterBalance()),
                    log.getIdempotencyKey(),
                    log.getRemark()
            ));
        }

        TaskCreditUsageSnapshot snap = toUsageSnapshot(actualUsage);

        return new TaskCreditDetailResponse(
                task.taskId(),
                emptyToDash(task.taskTitle()),
                task.taskType(),
                emptyToDash(task.provider()),
                emptyToDash(task.modelCode()),
                task.status(),
                task.estimatedCreditCost(),
                task.actualCreditCost(),
                paid,
                unpaid,
                emptyToDash(task.settlementStatus()),
                settlementLabel(task.settlementStatus()),
                task.createdAt(),
                task.updatedAt(),
                task.startedAt(),
                task.finishedAt(),
                snap,
                steps,
                explanation,
                logLines
        );
    }

    private Map<Long, TaskMini> loadTaskMeta(List<UserCreditLogEntity> logs) {
        Map<Long, TaskMini> meta = new HashMap<>();
        for (UserCreditLogEntity log : logs) {
            Long tid = log.getRelatedTaskId();
            if (tid == null || meta.containsKey(tid)) {
                continue;
            }
            try {
                TaskItem t = taskService.getTask(tid);
                meta.put(tid, new TaskMini(
                        emptyToDash(t.taskTitle()),
                        t.taskType() == null ? "" : t.taskType()));
            } catch (Exception ignored) {
                meta.put(tid, new TaskMini("任务#" + tid, ""));
            }
        }
        return meta;
    }

    private record TaskMini(String title, String taskType) {
    }

    private long sumOutstandingDebt(long taskId) {
        LambdaQueryWrapper<CreditDebtLogEntity> w = new LambdaQueryWrapper<>();
        w.eq(CreditDebtLogEntity::getTaskId, taskId)
                .eq(CreditDebtLogEntity::getDeleted, 0)
                .in(CreditDebtLogEntity::getStatus, CreditDebtStatus.UNPAID, CreditDebtStatus.PARTIAL_PAID);
        List<CreditDebtLogEntity> list = creditDebtLogMapper.selectList(w);
        long sum = 0L;
        for (CreditDebtLogEntity d : list) {
            long debt = safe(d.getDebtCredits());
            long paid = safe(d.getPaidCredits());
            sum += Math.max(0L, debt - paid);
        }
        return sum;
    }

    private List<TaskCreditStepRow> buildStepRows(String taskType, long totalEstimated, long actualTotal) {
        List<AiBillingStepConfigEntity> cfgSteps = billingStepConfigService.listEnabledSteps(taskType);
        if (cfgSteps.isEmpty()) {
            return List.of(new TaskCreditStepRow(
                    "整单计费",
                    "—",
                    "TASK",
                    "未配置分步计费，按任务类型固定或兜底规则预扣",
                    totalEstimated,
                    actualTotal > 0 ? actualTotal : null,
                    actualTotal > 0 ? "已结算" : "待结算或按预扣"
            ));
        }
        long sumStep = cfgSteps.stream().mapToLong(s -> safe(s.getCreditCost())).sum();
        List<TaskCreditStepRow> rows = new ArrayList<>();
        for (AiBillingStepConfigEntity s : cfgSteps) {
            long est = safe(s.getCreditCost());
            Long actualPart = null;
            String status = "配置项";
            if (actualTotal > 0 && sumStep > 0) {
                BigDecimal ratio = BigDecimal.valueOf(est).divide(BigDecimal.valueOf(sumStep), 8, RoundingMode.HALF_UP);
                actualPart = ratio.multiply(BigDecimal.valueOf(actualTotal)).setScale(0, RoundingMode.HALF_UP).longValue();
                status = "按预扣占比分摊实际消耗（展示用）";
            } else if (actualTotal > 0 && sumStep <= 0) {
                actualPart = actualTotal;
                status = "汇总至实际";
            }
            rows.add(new TaskCreditStepRow(
                    s.getStepName(),
                    formatModelApi(s.getProvider(), s.getModelCode()),
                    emptyToDash(s.getUsageUnit()),
                    stepUsageDisplay(s),
                    est,
                    actualPart,
                    status
            ));
        }
        return rows;
    }

    private List<String> buildExplanation(
            String taskType,
            List<TaskCreditStepRow> steps,
            long estimated,
            long actual,
            String settlementStatus) {
        List<String> lines = new ArrayList<>();
        lines.add("本次任务类型：" + taskType + "。");
        lines.add("本次任务在计费配置中包含以下步骤（预扣按各步骤积分相加）：");
        for (TaskCreditStepRow s : steps) {
            if ("整单计费".equals(s.stepName())) {
                lines.add("· " + s.usageDisplay());
                break;
            }
            lines.add("· " + s.stepName()
                    + "（" + s.modelApi() + "，计量：" + unitHint(s.usageUnit()) + "）");
        }
        lines.add("");
        lines.add("说明：提交任务时会按上表「预计积分」之和进行预扣；任务完成后系统根据真实用量（如 Token、时长、"
                + "第三方计费点数等）计算实际消耗，多退少补。");
        lines.add("当前记录：预扣约 " + estimated + " 积分"
                + (actual > 0 ? "；结算后实际消耗 " + actual + " 积分。" : "；实际消耗将在任务完成后写入。"));
        if (StringUtils.hasText(settlementStatus)) {
            lines.add("结算状态：" + settlementLabel(settlementStatus) + "（" + settlementStatus + "）。");
        }
        return lines;
    }

    private static String unitHint(String usageUnit) {
        if (!StringUtils.hasText(usageUnit) || "—".equals(usageUnit)) {
            return "按任务";
        }
        return switch (usageUnit.toUpperCase()) {
            case "TOKEN" -> "按 Token";
            case "CHAR" -> "按字符";
            case "IMAGE" -> "按张数";
            case "SECOND" -> "按时长（秒）";
            case "PROVIDER_CREDIT" -> "按第三方点数";
            case "TASK" -> "按次";
            default -> usageUnit;
        };
    }

    private static String stepUsageDisplay(AiBillingStepConfigEntity s) {
        if (StringUtils.hasText(s.getCallCount())) {
            return s.getCallCount();
        }
        if (StringUtils.hasText(s.getCostText())) {
            return s.getCostText();
        }
        return unitHint(s.getUsageUnit());
    }

    private static String formatModelApi(String provider, String modelCode) {
        String p = trimOrEmpty(provider);
        String m = trimOrEmpty(modelCode);
        if (p.isEmpty() && m.isEmpty()) {
            return "—";
        }
        if (p.isEmpty()) {
            return m;
        }
        if (m.isEmpty()) {
            return p;
        }
        return p + " / " + m;
    }

    private static TaskCreditUsageSnapshot toUsageSnapshot(AiUsageLogEntity u) {
        if (u == null) {
            return new TaskCreditUsageSnapshot(null, null, null, null, null, null, null, null);
        }
        return new TaskCreditUsageSnapshot(
                u.getPromptTokens(),
                u.getCompletionTokens(),
                u.getTotalTokens(),
                u.getCharacterCount(),
                u.getImageCount(),
                u.getDurationSeconds(),
                u.getProviderCredits(),
                u.getUsagePhase()
        );
    }

    private static String settlementLabel(String code) {
        if (!StringUtils.hasText(code)) {
            return "—";
        }
        return switch (code) {
            case SettlementStatus.NONE -> "无预扣";
            case SettlementStatus.PRECHARGED -> "已预扣";
            case SettlementStatus.SETTLED -> "已结算";
            case SettlementStatus.REFUNDED -> "已退款";
            case SettlementStatus.PARTIAL_REFUNDED -> "部分退款";
            case SettlementStatus.PARTIAL_SETTLED -> "部分结算（存在欠费）";
            case SettlementStatus.SETTLE_FAILED -> "结算异常";
            default -> code;
        };
    }

    private static Op classifyOperation(UserCreditLogEntity log) {
        String type = log.getChangeType() == null ? "" : log.getChangeType();
        String key = log.getIdempotencyKey() == null ? "" : log.getIdempotencyKey();
        if ("AI_REFUND".equals(type)) {
            if (key.startsWith("AI_SETTLE_REFUND:")) {
                return new Op("SETTLE_REFUND", "结算退款");
            }
            return new Op("REFUND", "退款");
        }
        if ("AI_CONSUME".equals(type)) {
            if (key.startsWith("AI_SETTLE_EXTRA:")) {
                return new Op("SETTLE_EXTRA", "补扣");
            }
            // 任务创建预扣可能使用用户 Idempotency-Key，未必以 AI_CONSUME: 开头；与 settle 补扣区分即可。
            return new Op("PRECHARGE", "预扣");
        }
        if ("ADMIN_ADD".equals(type)) {
            return new Op("ADMIN_ADD", "发放");
        }
        return new Op("OTHER", type.isEmpty() ? "其他" : type);
    }

    private record Op(String typeCode, String label) {
    }

    private static long safe(Long v) {
        return v == null ? 0L : v;
    }

    private static String trimOrNull(String p) {
        return p == null ? "" : p.trim();
    }

    private static String emptyToDash(String s) {
        return StringUtils.hasText(s) ? s : "—";
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
