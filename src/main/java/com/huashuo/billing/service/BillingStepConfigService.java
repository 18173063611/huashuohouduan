package com.huashuo.billing.service;

import com.huashuo.billing.entity.AiBillingStepConfigEntity;

import java.util.List;
import java.util.OptionalLong;

/**
 * 按 task_type 读取 {@code ai_billing_step_config} 启用步骤并汇总建议积分。
 * <p>用于在 {@link com.huashuo.task.service.TaskService#createTask} 创建任务时优先使用步骤累计积分，
 * 步骤为空时再回退到 {@link com.huashuo.task.config.TaskCreditProperties} 的固定积分。</p>
 */
public interface BillingStepConfigService {

    /**
     * 列出指定 task_type 下启用的步骤配置（按 sort_order）。
     */
    List<AiBillingStepConfigEntity> listEnabledSteps(String taskType);

    /**
     * 汇总启用步骤的 credit_cost。
     *
     * @return {@link OptionalLong#empty()} 表示该 task_type 没有任何启用的步骤配置，调用方应回退原 TaskCreditProperties。
     *         返回 0 或正数则视为该 task_type 已经被计费配置接管。
     */
    OptionalLong aggregateCreditCost(String taskType);
}
