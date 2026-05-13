package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.dto.AdminBillingStepSaveRequest;
import com.huashuo.admin.dto.AdminModelPriceSaveRequest;
import com.huashuo.admin.service.AdminBillingService;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.vo.AdminBillingStepItem;
import com.huashuo.admin.vo.AdminModelPriceItem;
import com.huashuo.billing.entity.AiBillingStepConfigEntity;
import com.huashuo.billing.entity.AiModelPriceEntity;
import com.huashuo.billing.mapper.AiBillingStepConfigMapper;
import com.huashuo.billing.mapper.AiModelPriceMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台 AI 计费配置管理实现：
 * <ul>
 *   <li>查询 / 启停 / 编辑 {@code ai_billing_step_config}（计费步骤）；</li>
 *   <li>查询 / 启停 / 编辑 {@code ai_model_price}（模型单价）。</li>
 * </ul>
 * 不调用 CreditService / CreditBillingService，仅修改配置表本身，
 * 因此即使后台误操作也不会立即影响已存在任务的预扣 / 退款 / 结算。
 */
@Service
public class AdminBillingServiceImpl implements AdminBillingService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final AiBillingStepConfigMapper stepConfigMapper;
    private final AiModelPriceMapper modelPriceMapper;
    private final AdminOperationAuditService auditService;

    public AdminBillingServiceImpl(AiBillingStepConfigMapper stepConfigMapper,
                                   AiModelPriceMapper modelPriceMapper,
                                   AdminOperationAuditService auditService) {
        this.stepConfigMapper = stepConfigMapper;
        this.modelPriceMapper = modelPriceMapper;
        this.auditService = auditService;
    }

    @Override
    public PageResult<AdminBillingStepItem> listSteps(String taskType, String functionModule, Boolean enabled,
                                                     Integer pageNo, Integer pageSize) {
        int page = normalizePage(pageNo);
        int size = normalizePageSize(pageSize);
        LambdaQueryWrapper<AiBillingStepConfigEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(taskType)) {
            wrapper.eq(AiBillingStepConfigEntity::getTaskType, taskType.trim());
        }
        if (StringUtils.hasText(functionModule)) {
            wrapper.like(AiBillingStepConfigEntity::getFunctionModule, functionModule.trim());
        }
        if (enabled != null) {
            wrapper.eq(AiBillingStepConfigEntity::getEnabled, enabled ? 1 : 0);
        }
        long total = stepConfigMapper.selectCount(wrapper);
        wrapper.orderByAsc(AiBillingStepConfigEntity::getTaskType)
                .orderByAsc(AiBillingStepConfigEntity::getSortOrder)
                .orderByAsc(AiBillingStepConfigEntity::getStepId)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminBillingStepItem> records = stepConfigMapper.selectList(wrapper).stream()
                .map(this::toStepItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    @Transactional
    public AdminBillingStepItem createStep(AdminBillingStepSaveRequest request, AdminOperationContext context) {
        AiBillingStepConfigEntity entity = new AiBillingStepConfigEntity();
        applyStepFields(entity, request, true);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (entity.getCreditCost() == null) {
            entity.setCreditCost(0L);
        }
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        if (entity.getFunctionModule() == null) {
            entity.setFunctionModule("");
        }
        stepConfigMapper.insert(entity);
        AdminBillingStepItem after = toStepItem(stepConfigMapper.selectById(entity.getStepId()));
        auditService.record(context, "BILLING_STEP_CREATE", "BILLING_STEP", entity.getStepId(), null, after);
        return after;
    }

    @Override
    @Transactional
    public AdminBillingStepItem updateStep(Long stepId, AdminBillingStepSaveRequest request,
                                           AdminOperationContext context) {
        AiBillingStepConfigEntity entity = requireStep(stepId);
        AdminBillingStepItem before = toStepItem(entity);
        applyStepFields(entity, request, false);
        entity.setUpdatedAt(LocalDateTime.now());
        stepConfigMapper.updateById(entity);
        AdminBillingStepItem after = toStepItem(stepConfigMapper.selectById(stepId));
        auditService.record(context, "BILLING_STEP_UPDATE", "BILLING_STEP", stepId, before, after);
        return after;
    }

    @Override
    @Transactional
    public AdminBillingStepItem setStepEnabled(Long stepId, boolean enabled, AdminOperationContext context) {
        AiBillingStepConfigEntity entity = requireStep(stepId);
        AdminBillingStepItem before = toStepItem(entity);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now());
        stepConfigMapper.updateById(entity);
        AdminBillingStepItem after = toStepItem(stepConfigMapper.selectById(stepId));
        auditService.record(context, enabled ? "BILLING_STEP_ENABLE" : "BILLING_STEP_DISABLE",
                "BILLING_STEP", stepId, before, after);
        return after;
    }

    @Override
    public PageResult<AdminModelPriceItem> listPrices(String provider, String taskType, Boolean enabled,
                                                     Integer pageNo, Integer pageSize) {
        int page = normalizePage(pageNo);
        int size = normalizePageSize(pageSize);
        LambdaQueryWrapper<AiModelPriceEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(provider)) {
            wrapper.eq(AiModelPriceEntity::getProvider, provider.trim());
        }
        if (StringUtils.hasText(taskType)) {
            wrapper.eq(AiModelPriceEntity::getTaskType, taskType.trim());
        }
        if (enabled != null) {
            wrapper.eq(AiModelPriceEntity::getEnabled, enabled ? 1 : 0);
        }
        long total = modelPriceMapper.selectCount(wrapper);
        wrapper.orderByAsc(AiModelPriceEntity::getProvider)
                .orderByAsc(AiModelPriceEntity::getModelCode)
                .orderByAsc(AiModelPriceEntity::getPriceId)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminModelPriceItem> records = modelPriceMapper.selectList(wrapper).stream()
                .map(this::toPriceItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    @Transactional
    public AdminModelPriceItem createPrice(AdminModelPriceSaveRequest request, AdminOperationContext context) {
        AiModelPriceEntity entity = new AiModelPriceEntity();
        applyPriceFields(entity, request, true);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        modelPriceMapper.insert(entity);
        AdminModelPriceItem after = toPriceItem(modelPriceMapper.selectById(entity.getPriceId()));
        auditService.record(context, "BILLING_PRICE_CREATE", "BILLING_PRICE", entity.getPriceId(), null, after);
        return after;
    }

    @Override
    @Transactional
    public AdminModelPriceItem updatePrice(Long priceId, AdminModelPriceSaveRequest request,
                                           AdminOperationContext context) {
        AiModelPriceEntity entity = requirePrice(priceId);
        AdminModelPriceItem before = toPriceItem(entity);
        applyPriceFields(entity, request, false);
        entity.setUpdatedAt(LocalDateTime.now());
        modelPriceMapper.updateById(entity);
        AdminModelPriceItem after = toPriceItem(modelPriceMapper.selectById(priceId));
        auditService.record(context, "BILLING_PRICE_UPDATE", "BILLING_PRICE", priceId, before, after);
        return after;
    }

    @Override
    @Transactional
    public AdminModelPriceItem setPriceEnabled(Long priceId, boolean enabled, AdminOperationContext context) {
        AiModelPriceEntity entity = requirePrice(priceId);
        AdminModelPriceItem before = toPriceItem(entity);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now());
        modelPriceMapper.updateById(entity);
        AdminModelPriceItem after = toPriceItem(modelPriceMapper.selectById(priceId));
        auditService.record(context, enabled ? "BILLING_PRICE_ENABLE" : "BILLING_PRICE_DISABLE",
                "BILLING_PRICE", priceId, before, after);
        return after;
    }

    /**
     * 新建 / 编辑共用：{@code createMode=true} 时强制写入必填字段；编辑模式下 null 字段沿用旧值，
     * 这样前端只关心“改动的字段”就能 PATCH 半行。
     */
    private void applyStepFields(AiBillingStepConfigEntity entity, AdminBillingStepSaveRequest request,
                                 boolean createMode) {
        if (createMode || request.taskType() != null) {
            entity.setTaskType(requireText(request.taskType(), "task_type"));
        }
        if (request.functionModule() != null) {
            entity.setFunctionModule(request.functionModule().trim());
        }
        if (createMode || request.stepName() != null) {
            entity.setStepName(requireText(request.stepName(), "step_name"));
        }
        if (request.provider() != null) {
            entity.setProvider(trimToNull(request.provider()));
        }
        if (request.modelCode() != null) {
            entity.setModelCode(trimToNull(request.modelCode()));
        }
        if (request.usageUnit() != null) {
            entity.setUsageUnit(trimToNull(request.usageUnit()));
        }
        if (request.callCount() != null) {
            entity.setCallCount(trimToNull(request.callCount()));
        }
        if (request.costText() != null) {
            entity.setCostText(trimToNull(request.costText()));
        }
        if (request.creditCost() != null) {
            entity.setCreditCost(Math.max(0L, request.creditCost()));
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled() ? 1 : 0);
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(request.sortOrder());
        }
        if (request.remark() != null) {
            entity.setRemark(trimToNull(request.remark()));
        }
    }

    private void applyPriceFields(AiModelPriceEntity entity, AdminModelPriceSaveRequest request, boolean createMode) {
        if (createMode || request.provider() != null) {
            entity.setProvider(requireText(request.provider(), "provider"));
        }
        if (createMode || request.modelCode() != null) {
            entity.setModelCode(requireText(request.modelCode(), "model_code"));
        }
        if (request.modelName() != null) {
            entity.setModelName(trimToNull(request.modelName()));
        }
        if (request.taskType() != null) {
            entity.setTaskType(trimToNull(request.taskType()));
        }
        if (request.usageUnit() != null) {
            entity.setUsageUnit(trimToNull(request.usageUnit()));
        }
        if (request.inputCreditPer1k() != null) {
            entity.setInputCreditPer1k(request.inputCreditPer1k());
        }
        if (request.outputCreditPer1k() != null) {
            entity.setOutputCreditPer1k(request.outputCreditPer1k());
        }
        if (request.unitCreditPrice() != null) {
            entity.setUnitCreditPrice(request.unitCreditPrice());
        }
        if (request.estimateOutputRatio() != null) {
            entity.setEstimateOutputRatio(request.estimateOutputRatio());
        }
        if (request.estimateBufferRatio() != null) {
            entity.setEstimateBufferRatio(request.estimateBufferRatio());
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled() ? 1 : 0);
        }
    }

    private AiBillingStepConfigEntity requireStep(Long stepId) {
        if (stepId == null) {
            throw new BusinessException(40000, "计费步骤 ID 不能为空");
        }
        AiBillingStepConfigEntity entity = stepConfigMapper.selectById(stepId);
        if (entity == null || (entity.getDeleted() != null && entity.getDeleted() == 1)) {
            throw new BusinessException(40400, "计费步骤不存在");
        }
        return entity;
    }

    private AiModelPriceEntity requirePrice(Long priceId) {
        if (priceId == null) {
            throw new BusinessException(40000, "模型单价 ID 不能为空");
        }
        AiModelPriceEntity entity = modelPriceMapper.selectById(priceId);
        if (entity == null || (entity.getDeleted() != null && entity.getDeleted() == 1)) {
            throw new BusinessException(40400, "模型单价不存在");
        }
        return entity;
    }

    private AdminBillingStepItem toStepItem(AiBillingStepConfigEntity entity) {
        return new AdminBillingStepItem(
                entity.getStepId(),
                entity.getTaskType(),
                entity.getFunctionModule(),
                entity.getStepName(),
                entity.getProvider(),
                entity.getModelCode(),
                entity.getUsageUnit(),
                entity.getCallCount(),
                entity.getCostText(),
                entity.getCreditCost(),
                entity.getEnabled() != null && entity.getEnabled() == 1,
                entity.getSortOrder(),
                entity.getRemark(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private AdminModelPriceItem toPriceItem(AiModelPriceEntity entity) {
        return new AdminModelPriceItem(
                entity.getPriceId(),
                entity.getProvider(),
                entity.getModelCode(),
                entity.getModelName(),
                entity.getTaskType(),
                entity.getUsageUnit(),
                entity.getInputCreditPer1k(),
                entity.getOutputCreditPer1k(),
                entity.getUnitCreditPrice(),
                entity.getEstimateOutputRatio(),
                entity.getEstimateBufferRatio(),
                entity.getEnabled() != null && entity.getEnabled() == 1,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private int normalizePage(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(40000, fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
