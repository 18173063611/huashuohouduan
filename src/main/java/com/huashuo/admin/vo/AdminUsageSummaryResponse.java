package com.huashuo.admin.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 后台 AI 用量与积分成本统计报表整体响应。
 *
 * @param dimension 统计维度：DATE / FUNCTION_MODULE / TASK_TYPE / PROVIDER / MODEL_CODE / USAGE_UNIT。
 * @param from      时间范围起始（含），用于审计页面展示。
 * @param to        时间范围结束（含），用于审计页面展示。
 * @param rows      分组明细，按维度自然序排序。
 * @param total     全量合计行，方便管理员一眼看到大盘总额。
 */
public record AdminUsageSummaryResponse(
        String dimension,
        LocalDate from,
        LocalDate to,
        List<AdminUsageSummaryRow> rows,
        AdminUsageSummaryRow total
) {
}
