package com.huashuo.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.billing.dto.UsageSummaryPerTaskRow;
import com.huashuo.billing.entity.AiUsageLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiUsageLogMapper extends BaseMapper<AiUsageLogEntity> {

    /**
     * 按 {@code task_id} 折叠 ai_usage_log：每个任务最多返回一行，ESTIMATE 与 ACTUAL 的值用 CASE WHEN 拆开统计，
     * 避免 ESTIMATE 占位行的零值字段污染 ACTUAL 真实用量字段。
     *
     * <p>过滤条件全部允许 {@code null}：调用方传 {@code null} 时该条件被跳过；时间范围采用 {@code [from, to)}，
     * 由上层 Service 把界面上"截止日"扩展为次日 00:00:00 后传入，避免遗漏当天数据。</p>
     *
     * <p>该查询同时兼容 H2 与 MySQL：{@code DATE(...)} 在两者中都返回 {@link java.sql.Date}，被 MyBatis 自动
     * 映射为 {@link java.time.LocalDate}；{@code COALESCE(SUM(...), 0)} 保证空集时返回 0 而不是 NULL。</p>
     */
    @Select({
            "<script>",
            "SELECT",
            "  task_id AS taskId,",
            "  DATE(MIN(created_at)) AS reportDate,",
            "  MAX(task_type) AS taskType,",
            "  MAX(provider) AS provider,",
            "  MAX(model_code) AS modelCode,",
            "  MAX(usage_unit) AS usageUnit,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ESTIMATE' THEN estimated_credit_cost ELSE 0 END), 0) AS estimatedCreditCost,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN actual_credit_cost   ELSE 0 END), 0) AS actualCreditCost,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN prompt_tokens        ELSE 0 END), 0) AS promptTokens,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN completion_tokens    ELSE 0 END), 0) AS completionTokens,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN total_tokens         ELSE 0 END), 0) AS totalTokens,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN character_count      ELSE 0 END), 0) AS characterCount,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN image_count          ELSE 0 END), 0) AS imageCount,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN duration_seconds     ELSE 0 END), 0) AS durationSeconds,",
            "  COALESCE(SUM(CASE WHEN usage_phase = 'ACTUAL'   THEN provider_credits     ELSE 0 END), 0) AS providerCredits",
            "FROM ai_usage_log",
            "WHERE deleted = 0",
            "  AND task_id IS NOT NULL",
            "  <if test='from != null'>AND created_at &gt;= #{from}</if>",
            "  <if test='to   != null'>AND created_at &lt;  #{to}</if>",
            "  <if test='taskType   != null and taskType   != \"\"'>AND task_type   = #{taskType}</if>",
            "  <if test='provider   != null and provider   != \"\"'>AND provider    = #{provider}</if>",
            "  <if test='modelCode  != null and modelCode  != \"\"'>AND model_code  = #{modelCode}</if>",
            "  <if test='usageUnit  != null and usageUnit  != \"\"'>AND usage_unit  = #{usageUnit}</if>",
            "GROUP BY task_id",
            "</script>"
    })
    List<UsageSummaryPerTaskRow> aggregatePerTask(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("taskType") String taskType,
            @Param("provider") String provider,
            @Param("modelCode") String modelCode,
            @Param("usageUnit") String usageUnit
    );
}
