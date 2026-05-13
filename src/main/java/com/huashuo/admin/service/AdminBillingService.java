package com.huashuo.admin.service;

import com.huashuo.admin.dto.AdminBillingStepSaveRequest;
import com.huashuo.admin.dto.AdminModelPriceSaveRequest;
import com.huashuo.admin.vo.AdminBillingStepItem;
import com.huashuo.admin.vo.AdminModelPriceItem;
import com.huashuo.common.response.PageResult;

/**
 * 后台 AI 计费配置管理：仅暴露 ai_billing_step_config 与 ai_model_price 的查询 / 编辑 / 启用-禁用，
 * <strong>不会触碰现有 user_credit_log、CreditService、CreditBillingService 任何扣费链路</strong>。
 */
public interface AdminBillingService {

    /** 计费步骤列表，支持按 task_type / function_module / enabled 过滤。 */
    PageResult<AdminBillingStepItem> listSteps(String taskType, String functionModule, Boolean enabled,
                                               Integer pageNo, Integer pageSize);

    AdminBillingStepItem createStep(AdminBillingStepSaveRequest request, AdminOperationContext context);

    /** 仅修改提供的字段；DTO 中 null 字段默认沿用旧值，creditCost 通过 @Min(0) 校验。 */
    AdminBillingStepItem updateStep(Long stepId, AdminBillingStepSaveRequest request,
                                    AdminOperationContext context);

    /** 切换 enabled。禁用项不会参与 task 创建时积分汇总，但历史 task / usage_log 不受影响。 */
    AdminBillingStepItem setStepEnabled(Long stepId, boolean enabled, AdminOperationContext context);

    PageResult<AdminModelPriceItem> listPrices(String provider, String taskType, Boolean enabled,
                                               Integer pageNo, Integer pageSize);

    AdminModelPriceItem createPrice(AdminModelPriceSaveRequest request, AdminOperationContext context);

    AdminModelPriceItem updatePrice(Long priceId, AdminModelPriceSaveRequest request,
                                    AdminOperationContext context);

    AdminModelPriceItem setPriceEnabled(Long priceId, boolean enabled, AdminOperationContext context);
}
