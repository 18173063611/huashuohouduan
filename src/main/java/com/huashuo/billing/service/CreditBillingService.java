package com.huashuo.billing.service;

import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageEstimateResult;
import com.huashuo.user.service.CreditChangeResult;

public interface CreditBillingService {

    CreditChangeResult precharge(Long userId, Long taskId, UsageEstimateResult estimate,
                                 String idempotencyKey, String remark);

    void settle(Long taskId, UsageActualResult actualUsage);

    /**
     * 在尚未拿到真实 usage 之前，追加一条 {@code usage_phase = ESTIMATE} 的 {@code ai_usage_log} 占位记录，
     * 用于后续运营对账与未来 actual usage 结算的初始定位行。第一版仅写
     * task_id / user_id / task_type / provider / model_code / usage_unit / estimated_credit_cost。
     * <p>actual_credit_cost 置 0，等业务侧拿到实际用量后调用 {@link #settle} 或 {@link #recordActual}
     * 追加 {@code usage_phase = ACTUAL} 行。</p>
     *
     * @param userId 任务发起人；匿名任务允许传 {@code null}，仍会写入便于按 taskId 分析。
     * @return 新增的 usage_log 主键。
     */
    Long recordEstimate(Long userId, Long taskId, String taskType, UsageEstimateResult estimate);

    /**
     * 仅记录真实用量「轻量结算」：写一条 {@code usage_phase = ACTUAL} 的 {@code ai_usage_log} 行，
     * 同步更新 {@code task.actual_usage} 与 {@code task.provider / model_code / usage_unit}（若 actualUsage 中给出），
     * 但<strong>不做任何积分补扣 / 退款 / settlement_status 变更</strong>。
     *
     * <p>适用于「拿不到真实 usage 但又想留痕」的场景，例如 Seedance 当前返回值里没有视频时长。
     * 后续若拿到准确用量再调用 {@link #settle} 走完整对账流程。</p>
     *
     * @return 写入的 usage_log 主键；taskId 为空或任务不存在时返回 {@code null}。
     */
    Long recordActual(Long taskId, UsageActualResult actualUsage);

    /**
     * 「失败但不退款」结算：用于第三方已受理（已经产生外部费用）但任务最终失败的场景。
     * 不动余额、不写 user_credit_log，只做账面收尾：
     * <ul>
     *   <li>把预扣积分作为实际成本"消费掉"：task.actualCreditCost = task.estimatedCreditCost；</li>
     *   <li>settlement_status 从 PRECHARGED 推进到 SETTLED，便于报表与对账唯一识别此类任务；</li>
     *   <li>写一条 ai_usage_log usage_phase=ACTUAL 占位记录，actual_credit_cost = estimated_credit_cost，
     *       raw_usage_json 中带 {@code failReason / refundCredits=false / thirdPartyAccepted=true} 上下文。</li>
     * </ul>
     * <p>幂等：任务已经处于 SETTLED / REFUNDED / PARTIAL_REFUNDED 任一终态时直接 short-circuit。</p>
     *
     * @return 新写入的 usage_log 主键；任务不存在 / 已终态 / 预扣金额为 0 时返回 {@code null}。
     */
    Long recordConsumeWithoutRefund(Long taskId, String failReason);
}
