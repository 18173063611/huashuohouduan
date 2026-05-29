package com.huashuo.billing.service;

import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;

/**
 * 统一的"预计消耗"计算入口：前端预估接口与 {@code TaskService.createTask} 的实际预扣，
 * 必须复用本服务的同一份逻辑，避免出现"前端展示 5 实扣 20"的双源冲突。
 *
 * <p>固定步骤任务的调用顺序（与 {@code TaskServiceImpl.resolveCreditCost} 完全对齐）：
 * <ol>
 *   <li>调用方显式 override：{@code creditCostOverride != null} 时直接返回该值。</li>
 *   <li>{@link BillingStepConfigService#aggregateCreditCost(String)}：按 {@code ai_billing_step_config}
 *       中 {@code enabled=1} 步骤的 {@code credit_cost} 汇总。</li>
 *   <li>{@link com.huashuo.task.config.TaskCreditProperties#costFor(String)}：兜底固定积分。</li>
 * </ol>
 *
 * <p>{@code estimate(...)} 在固定步骤基础上，还会对 TTS / 试听 / 形象生成按输入用量动态预估，
 * 对汽车销售成片按段数预估，并回填 provider / modelCode / usageUnit / 启用步骤明细，以及
 * （若请求带 ownerUserId）账户余额与是否充足，便于前端一次拿全展示数据。</p>
 */
public interface BillingEstimateService {

    /**
     * 计算单个 task_type 的预扣总积分。
     *
     * @param taskType            任务类型代码，参考 {@link com.huashuo.task.enums.TaskTypeCode}。
     * @param creditCostOverride  调用方显式覆盖；{@code null} 表示走 step config / properties 链路。
     * @return                    最终预扣积分（>= 0）。
     */
    long resolveCreditCost(String taskType, Long creditCostOverride);

    /**
     * 计算预估并附带步骤明细、provider / modelCode / usageUnit、可选的余额信息。
     */
    BillingEstimateResponse estimate(BillingEstimateRequest request);
}
