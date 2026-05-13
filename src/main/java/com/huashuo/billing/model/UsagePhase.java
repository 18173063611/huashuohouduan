package com.huashuo.billing.model;

/**
 * {@code ai_usage_log.usage_phase} 取值集合：
 * <ul>
 *   <li>{@link #ESTIMATE} - 任务创建时按 {@code ai_billing_step_config} 汇总积分写入的估算占位行，
 *       actual_credit_cost = 0，duration_seconds = 0，仅用于对账起点。</li>
 *   <li>{@link #ACTUAL} - 任务结束后写入的实际用量行。若拿不到真实 usage（例如 Seedance 暂未暴露视频时长），
 *       仍写一条 ACTUAL 占位以保留 provider/modelCode/videoUrl 等元信息，但不会触发预扣或退款。</li>
 * </ul>
 */
public final class UsagePhase {

    private UsagePhase() {
    }

    public static final String ESTIMATE = "ESTIMATE";
    public static final String ACTUAL = "ACTUAL";
}
