package com.huashuo.billing.model;

import java.util.List;

/**
 * 统一的预估响应：前端"预计消耗"展示、提交前余额校验、报表与对账都基于这个数据结构。
 *
 * <p>{@code estimatedCreditCost} 与 {@link com.huashuo.task.service.TaskService#createTask}
 * 的实际预扣金额来自同一计算路径：固定步骤任务走 {@code ai_billing_step_config} 汇总和
 * {@link com.huashuo.task.config.TaskCreditProperties} 兜底；用量明确的任务可按模型单价或段数动态估算，
 * 保证不会出现"前端 5 实扣 20"。</p>
 *
 * @param pricingSource 来源标记：{@code BILLING_STEP_CONFIG} 表示走 ai_billing_step_config 汇总；
 *                      {@code TASK_CREDIT_PROPERTIES} 表示走应用配置兜底；
 *                      {@code USAGE_MODEL_PRICE} 表示按已知输入用量和模型单价动态估算；
 *                      {@code SEGMENT_COUNT} 表示按汽车销售成片段数估算；
 *                      {@code OVERRIDE} 表示调用方显式覆盖。
 * @param balance       当前用户的可用积分余额；{@code null} 表示请求未携带用户上下文（公开接口）。
 * @param enoughBalance 余额是否够本次预扣；{@code null} 表示无用户上下文。
 */
public record BillingEstimateResponse(
        String taskType,
        long estimatedCreditCost,
        String usageUnit,
        String modelCode,
        String provider,
        String pricingSource,
        Long balance,
        Boolean enoughBalance,
        List<BillingEstimateStep> steps
) {

    /** 预估来源：来自 ai_billing_step_config 启用步骤汇总。 */
    public static final String SOURCE_BILLING_STEP_CONFIG = "BILLING_STEP_CONFIG";
    /** 预估来源：来自应用配置 task.credit 兜底。 */
    public static final String SOURCE_TASK_CREDIT_PROPERTIES = "TASK_CREDIT_PROPERTIES";
    /** 预估来源：来自模型单价和本次输入用量。 */
    public static final String SOURCE_USAGE_MODEL_PRICE = "USAGE_MODEL_PRICE";
    /** 预估来源：来自汽车销售成片段数。 */
    public static final String SOURCE_SEGMENT_COUNT = "SEGMENT_COUNT";
    /** 预估来源：调用方显式 override（极少使用）。 */
    public static final String SOURCE_OVERRIDE = "OVERRIDE";

    public record BillingEstimateStep(
            String stepName,
            String functionModule,
            long creditCost,
            boolean enabled,
            String usageUnit,
            String modelCode,
            String provider,
            String costText
    ) {
    }
}
