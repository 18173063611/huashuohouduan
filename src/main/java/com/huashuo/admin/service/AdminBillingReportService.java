package com.huashuo.admin.service;

import com.huashuo.admin.vo.AdminUsageSummaryResponse;

import java.time.LocalDate;

/**
 * 后台 AI 用量与积分成本统计报表查询服务。
 *
 * <p>从 {@code ai_usage_log} 出发：先把同一 task_id 的 ESTIMATE/ACTUAL 行折叠为一行，再按维度二次汇总。
 * 不修改任何业务数据，因此可以安全地在任何环境上调用。</p>
 */
public interface AdminBillingReportService {

    /** 报表统计维度。 */
    enum Dimension {
        DATE,
        FUNCTION_MODULE,
        TASK_TYPE,
        PROVIDER,
        MODEL_CODE,
        USAGE_UNIT
    }

    /**
     * 计算 AI 用量与成本统计报表。
     *
     * @param dimension      统计维度，{@code null} 时按 {@link Dimension#DATE} 返回。
     * @param from           起始日期（含），{@code null} 时默认 30 天前。
     * @param to             截止日期（含），{@code null} 时默认今天。
     * @param taskType       仅统计该 task_type；{@code null} / 空串表示不限。
     * @param functionModule 仅统计该功能模块（来自 {@code ai_billing_step_config.function_module}）；{@code null} 不限。
     * @param provider       仅统计该 provider；{@code null} 不限。
     * @param modelCode      仅统计该 model_code；{@code null} 不限。
     * @param usageUnit      仅统计该 usage_unit；{@code null} 不限。
     */
    AdminUsageSummaryResponse usageSummary(
            Dimension dimension,
            LocalDate from,
            LocalDate to,
            String taskType,
            String functionModule,
            String provider,
            String modelCode,
            String usageUnit
    );

    /**
     * 与 {@link #usageSummary} 同输入，但输出 CSV 文本（UTF-8，含 BOM 便于 Excel 直接打开），
     * 由控制器以 {@code text/csv} 流式返回。
     */
    String exportUsageSummaryCsv(
            Dimension dimension,
            LocalDate from,
            LocalDate to,
            String taskType,
            String functionModule,
            String provider,
            String modelCode,
            String usageUnit
    );
}
