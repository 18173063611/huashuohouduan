package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.service.AdminBillingReportService;
import com.huashuo.admin.vo.AdminUsageSummaryResponse;
import com.huashuo.admin.vo.AdminUsageSummaryRow;
import com.huashuo.billing.dto.UsageSummaryPerTaskRow;
import com.huashuo.billing.entity.AiBillingStepConfigEntity;
import com.huashuo.billing.mapper.AiBillingStepConfigMapper;
import com.huashuo.billing.mapper.AiUsageLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 用量与积分成本统计报表实现。
 *
 * <p>实现策略：
 * <ol>
 *   <li>SQL 层只做"按 task_id 折叠"，每个任务返回一行 ESTIMATE+ACTUAL 拼合后的数值；</li>
 *   <li>Java 层做二次分组，按 {@link Dimension} 把行 reduce 到目标维度；</li>
 *   <li>{@code FUNCTION_MODULE} 维度需要 {@code ai_billing_step_config} 提供 task_type → function_module 的映射，
 *       这里取每个 task_type 下 sort_order 最小、enabled=1 的步骤的 function_module，与种子数据中"主步骤"语义一致。</li>
 * </ol>
 *
 * <p>"最终成本"规则：每个任务取
 * <code>finalCost = actualCreditCost > 0 ? actualCreditCost : estimatedCreditCost</code>，再按维度求和；
 * 这保证已结算任务用真实积分、未结算任务用估算积分，与 SettlementStatus 设计一致。</p>
 */
@Service
public class AdminBillingReportServiceImpl implements AdminBillingReportService {

    private static final int DEFAULT_RANGE_DAYS = 30;

    private final AiUsageLogMapper aiUsageLogMapper;
    private final AiBillingStepConfigMapper billingStepConfigMapper;

    public AdminBillingReportServiceImpl(AiUsageLogMapper aiUsageLogMapper,
                                         AiBillingStepConfigMapper billingStepConfigMapper) {
        this.aiUsageLogMapper = aiUsageLogMapper;
        this.billingStepConfigMapper = billingStepConfigMapper;
    }

    @Override
    public AdminUsageSummaryResponse usageSummary(Dimension dimension, LocalDate from, LocalDate to,
                                                  String taskType, String functionModule,
                                                  String provider, String modelCode, String usageUnit) {
        Dimension resolvedDimension = dimension == null ? Dimension.DATE : dimension;
        LocalDate resolvedTo = to == null ? LocalDate.now() : to;
        LocalDate resolvedFrom = from == null ? resolvedTo.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        if (resolvedFrom.isAfter(resolvedTo)) {
            LocalDate tmp = resolvedFrom;
            resolvedFrom = resolvedTo;
            resolvedTo = tmp;
        }
        // 数据库使用 [from, toExclusive) 半开区间，截止日扩展到次日 00:00 避免漏当天数据。
        LocalDateTime fromDateTime = resolvedFrom.atStartOfDay();
        LocalDateTime toDateTime = resolvedTo.plusDays(1).atStartOfDay();

        Map<String, String> taskTypeToFunctionModule = loadTaskTypeFunctionModuleMap();

        List<UsageSummaryPerTaskRow> perTaskRows = aiUsageLogMapper.aggregatePerTask(
                fromDateTime, toDateTime,
                trimToNull(taskType), trimToNull(provider), trimToNull(modelCode), trimToNull(usageUnit)
        );

        // FUNCTION_MODULE 维度的过滤在 Java 层进行，因为 ai_usage_log 没有 function_module 列。
        String moduleFilter = trimToNull(functionModule);

        Map<String, Accumulator> grouped = new LinkedHashMap<>();
        Accumulator totals = new Accumulator();
        for (UsageSummaryPerTaskRow row : perTaskRows) {
            String functionModuleOfRow = taskTypeToFunctionModule.getOrDefault(row.taskType(), "");
            if (moduleFilter != null && !moduleFilter.equalsIgnoreCase(functionModuleOfRow)) {
                continue;
            }
            String groupKey = resolveGroupKey(resolvedDimension, row, functionModuleOfRow);
            grouped.computeIfAbsent(groupKey, k -> new Accumulator()).accumulate(row);
            totals.accumulate(row);
        }

        List<AdminUsageSummaryRow> rows = new ArrayList<>(grouped.size());
        grouped.forEach((key, acc) -> rows.add(acc.toRow(key, key)));
        rows.sort(comparatorFor(resolvedDimension));

        AdminUsageSummaryRow totalRow = totals.toRow("__TOTAL__", "全部");
        return new AdminUsageSummaryResponse(resolvedDimension.name(), resolvedFrom, resolvedTo, rows, totalRow);
    }

    @Override
    public String exportUsageSummaryCsv(Dimension dimension, LocalDate from, LocalDate to,
                                        String taskType, String functionModule, String provider,
                                        String modelCode, String usageUnit) {
        AdminUsageSummaryResponse response = usageSummary(dimension, from, to, taskType, functionModule,
                provider, modelCode, usageUnit);
        StringBuilder sb = new StringBuilder();
        // Excel 友好的 UTF-8 BOM，便于中文不乱码。
        sb.append('\uFEFF');
        sb.append("dimension,from,to\n");
        sb.append(csv(response.dimension())).append(',')
                .append(csv(String.valueOf(response.from()))).append(',')
                .append(csv(String.valueOf(response.to()))).append('\n');
        sb.append('\n');
        sb.append("group,call_count,estimated_credit_cost,actual_credit_cost,final_credit_cost,")
                .append("prompt_tokens,completion_tokens,total_tokens,character_count,image_count,")
                .append("duration_seconds,provider_credits\n");
        for (AdminUsageSummaryRow row : response.rows()) {
            appendCsvRow(sb, row);
        }
        appendCsvRow(sb, response.total());
        return sb.toString();
    }

    private Map<String, String> loadTaskTypeFunctionModuleMap() {
        LambdaQueryWrapper<AiBillingStepConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiBillingStepConfigEntity::getDeleted, 0)
                .orderByAsc(AiBillingStepConfigEntity::getSortOrder)
                .orderByAsc(AiBillingStepConfigEntity::getStepId);
        List<AiBillingStepConfigEntity> list = billingStepConfigMapper.selectList(wrapper);
        // 优先取 enabled=1 的"首个步骤"作为该 task_type 的展示功能模块；都未启用时仍可回落到第一条。
        Map<String, String> primaryEnabled = new HashMap<>();
        Map<String, String> fallbackAny = new HashMap<>();
        for (AiBillingStepConfigEntity step : list) {
            String taskType = step.getTaskType();
            String functionModule = step.getFunctionModule() == null ? "" : step.getFunctionModule();
            fallbackAny.putIfAbsent(taskType, functionModule);
            if (step.getEnabled() != null && step.getEnabled() == 1) {
                primaryEnabled.putIfAbsent(taskType, functionModule);
            }
        }
        Map<String, String> result = new HashMap<>(fallbackAny);
        result.putAll(primaryEnabled);
        return result;
    }

    private String resolveGroupKey(Dimension dimension, UsageSummaryPerTaskRow row, String functionModuleOfRow) {
        return switch (dimension) {
            case DATE -> row.reportDate() == null ? "未知日期" : row.reportDate().toString();
            case FUNCTION_MODULE -> StringUtils.hasText(functionModuleOfRow) ? functionModuleOfRow : "未配置";
            case TASK_TYPE -> StringUtils.hasText(row.taskType()) ? row.taskType() : "UNKNOWN";
            case PROVIDER -> StringUtils.hasText(row.provider()) ? row.provider() : "UNKNOWN";
            case MODEL_CODE -> StringUtils.hasText(row.modelCode()) ? row.modelCode() : "UNKNOWN";
            case USAGE_UNIT -> StringUtils.hasText(row.usageUnit()) ? row.usageUnit() : "UNKNOWN";
        };
    }

    private Comparator<AdminUsageSummaryRow> comparatorFor(Dimension dimension) {
        if (dimension == Dimension.DATE) {
            // 日期维度按日期升序，便于折线图直接消费。
            return Comparator.comparing(AdminUsageSummaryRow::groupKey);
        }
        // 其他维度按 finalCreditCost 降序，金额大者在前，方便运营首屏看重点。
        return Comparator.comparingLong(AdminUsageSummaryRow::finalCreditCost).reversed();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void appendCsvRow(StringBuilder sb, AdminUsageSummaryRow row) {
        sb.append(csv(row.groupLabel())).append(',')
                .append(row.callCount()).append(',')
                .append(row.estimatedCreditCost()).append(',')
                .append(row.actualCreditCost()).append(',')
                .append(row.finalCreditCost()).append(',')
                .append(row.promptTokens()).append(',')
                .append(row.completionTokens()).append(',')
                .append(row.totalTokens()).append(',')
                .append(row.characterCount()).append(',')
                .append(row.imageCount()).append(',')
                .append(row.durationSeconds() == null ? "0" : row.durationSeconds().toPlainString()).append(',')
                .append(row.providerCredits() == null ? "0" : row.providerCredits().toPlainString())
                .append('\n');
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * 累加器：把同一分组的多条 {@link UsageSummaryPerTaskRow} 累加成一个 {@link AdminUsageSummaryRow}。
     * 这里特别处理"最终成本"规则——每条任务级别先决定 finalCost，再累加到组的 finalCreditCost。
     */
    private static final class Accumulator {
        private long callCount;
        private long estimated;
        private long actual;
        private long finalCost;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long characterCount;
        private long imageCount;
        private BigDecimal durationSeconds = BigDecimal.ZERO;
        private BigDecimal providerCredits = BigDecimal.ZERO;

        void accumulate(UsageSummaryPerTaskRow row) {
            callCount += 1;
            long est = nz(row.estimatedCreditCost());
            long act = nz(row.actualCreditCost());
            estimated += est;
            actual += act;
            finalCost += act > 0 ? act : est;
            promptTokens += nz(row.promptTokens());
            completionTokens += nz(row.completionTokens());
            totalTokens += nz(row.totalTokens());
            characterCount += nz(row.characterCount());
            imageCount += nz(row.imageCount());
            durationSeconds = durationSeconds.add(nz(row.durationSeconds()));
            providerCredits = providerCredits.add(nz(row.providerCredits()));
        }

        AdminUsageSummaryRow toRow(String key, String label) {
            return new AdminUsageSummaryRow(
                    key, label,
                    callCount,
                    estimated, actual, finalCost,
                    promptTokens, completionTokens, totalTokens,
                    characterCount, imageCount,
                    durationSeconds, providerCredits
            );
        }

        private static long nz(Long value) {
            return value == null ? 0L : value;
        }

        private static BigDecimal nz(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
