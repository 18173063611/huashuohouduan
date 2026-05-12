package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.admin.dto.AdminModelSaveRequest;
import com.huashuo.admin.entity.AiModelConfigEntity;
import com.huashuo.admin.mapper.AiModelConfigMapper;
import com.huashuo.admin.service.AdminModelService;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.vo.AdminModelItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminModelServiceImpl implements AdminModelService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final AiModelConfigMapper aiModelConfigMapper;
    private final AdminOperationAuditService auditService;

    public AdminModelServiceImpl(AiModelConfigMapper aiModelConfigMapper, AdminOperationAuditService auditService) {
        this.aiModelConfigMapper = aiModelConfigMapper;
        this.auditService = auditService;
    }

    @Override
    public PageResult<AdminModelItem> listModels(String modelType, String provider, Boolean enabled,
                                                 Integer pageNo, Integer pageSize) {
        int page = normalizePage(pageNo);
        int size = normalizePageSize(pageSize);
        LambdaQueryWrapper<AiModelConfigEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(modelType)) {
            wrapper.eq(AiModelConfigEntity::getModelType, modelType.trim().toUpperCase());
        }
        if (StringUtils.hasText(provider)) {
            wrapper.eq(AiModelConfigEntity::getProvider, provider.trim().toUpperCase());
        }
        if (enabled != null) {
            wrapper.eq(AiModelConfigEntity::getEnabled, enabled ? 1 : 0);
        }
        long total = aiModelConfigMapper.selectCount(wrapper);
        wrapper.orderByAsc(AiModelConfigEntity::getModelType)
                .orderByDesc(AiModelConfigEntity::getDefaultModel)
                .orderByDesc(AiModelConfigEntity::getUpdatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminModelItem> records = aiModelConfigMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    @Transactional
    public AdminModelItem saveModel(AdminModelSaveRequest request, AdminOperationContext context) {
        String modelCode = normalizeRequired(request.modelCode(), "模型编码");
        ensureModelCodeAvailable(modelCode, null);
        AiModelConfigEntity entity = new AiModelConfigEntity();
        fillEntity(entity, request);
        entity.setModelCode(modelCode);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        aiModelConfigMapper.insert(entity);
        if (entity.getDefaultModel() != null && entity.getDefaultModel() == 1) {
            clearOtherDefault(entity);
        }
        AdminModelItem after = toItem(aiModelConfigMapper.selectById(entity.getModelId()));
        auditService.record(context, "MODEL_CREATE", "MODEL", entity.getModelId(), null, after);
        return after;
    }

    @Override
    @Transactional
    public AdminModelItem updateModel(Long modelId, AdminModelSaveRequest request, AdminOperationContext context) {
        AiModelConfigEntity entity = requireModel(modelId);
        AdminModelItem before = toItem(entity);
        String modelCode = normalizeRequired(request.modelCode(), "模型编码");
        ensureModelCodeAvailable(modelCode, modelId);
        fillEntity(entity, request);
        entity.setModelCode(modelCode);
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(entity);
        if (entity.getDefaultModel() != null && entity.getDefaultModel() == 1) {
            clearOtherDefault(entity);
        }
        AdminModelItem after = toItem(aiModelConfigMapper.selectById(modelId));
        auditService.record(context, "MODEL_UPDATE", "MODEL", modelId, before, after);
        return after;
    }

    @Override
    @Transactional
    public AdminModelItem setEnabled(Long modelId, boolean enabled, AdminOperationContext context) {
        AiModelConfigEntity entity = requireModel(modelId);
        AdminModelItem before = toItem(entity);
        entity.setEnabled(enabled ? 1 : 0);
        if (!enabled) {
            entity.setDefaultModel(0);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(entity);
        AdminModelItem after = toItem(aiModelConfigMapper.selectById(modelId));
        auditService.record(context, enabled ? "MODEL_ENABLE" : "MODEL_DISABLE", "MODEL", modelId, before, after);
        return after;
    }

    @Override
    @Transactional
    public AdminModelItem setDefault(Long modelId, AdminOperationContext context) {
        AiModelConfigEntity entity = requireModel(modelId);
        AdminModelItem before = toItem(entity);
        if (entity.getEnabled() == null || entity.getEnabled() == 0) {
            throw new BusinessException(40900, "禁用模型不能设为默认");
        }
        entity.setDefaultModel(1);
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(entity);
        clearOtherDefault(entity);
        AdminModelItem after = toItem(aiModelConfigMapper.selectById(modelId));
        auditService.record(context, "MODEL_SET_DEFAULT", "MODEL", modelId, before, after);
        return after;
    }

    private void fillEntity(AiModelConfigEntity entity, AdminModelSaveRequest request) {
        entity.setModelName(normalizeRequired(request.modelName(), "模型名称"));
        entity.setModelType(normalizeRequired(request.modelType(), "模型类型").toUpperCase());
        entity.setProvider(normalizeRequired(request.provider(), "供应商").toUpperCase());
        entity.setProviderModel(trimToNull(request.providerModel()));
        entity.setCreditCost(request.creditCost() == null ? 0L : Math.max(0L, request.creditCost()));
        entity.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        entity.setDefaultModel(Boolean.TRUE.equals(request.defaultModel()) ? 1 : 0);
        entity.setCapabilityJson(trimToNull(request.capabilityJson()));
        entity.setDefaultParamsJson(trimToNull(request.defaultParamsJson()));
        entity.setRateLimitPerMinute(request.rateLimitPerMinute());
        entity.setConcurrencyLimit(request.concurrencyLimit());
    }

    private void clearOtherDefault(AiModelConfigEntity entity) {
        LambdaUpdateWrapper<AiModelConfigEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AiModelConfigEntity::getModelType, entity.getModelType())
                .ne(AiModelConfigEntity::getModelId, entity.getModelId())
                .eq(AiModelConfigEntity::getDeleted, 0)
                .set(AiModelConfigEntity::getDefaultModel, 0)
                .set(AiModelConfigEntity::getUpdatedAt, LocalDateTime.now());
        aiModelConfigMapper.update(null, update);
    }

    private AiModelConfigEntity requireModel(Long modelId) {
        if (modelId == null) {
            throw new BusinessException(40000, "模型 ID 不能为空");
        }
        AiModelConfigEntity entity = aiModelConfigMapper.selectById(modelId);
        if (entity == null || entity.getDeleted() != null && entity.getDeleted() == 1) {
            throw new BusinessException(40400, "模型配置不存在");
        }
        return entity;
    }

    private void ensureModelCodeAvailable(String modelCode, Long currentModelId) {
        LambdaQueryWrapper<AiModelConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiModelConfigEntity::getModelCode, modelCode)
                .eq(AiModelConfigEntity::getDeleted, 0)
                .last("limit 1");
        AiModelConfigEntity existing = aiModelConfigMapper.selectOne(wrapper);
        if (existing != null && !existing.getModelId().equals(currentModelId)) {
            throw new BusinessException(40900, "模型编码已存在");
        }
    }

    private AdminModelItem toItem(AiModelConfigEntity entity) {
        return new AdminModelItem(
                entity.getModelId(),
                entity.getModelCode(),
                entity.getModelName(),
                entity.getModelType(),
                entity.getProvider(),
                entity.getProviderModel(),
                entity.getCreditCost(),
                entity.getEnabled() != null && entity.getEnabled() == 1,
                entity.getDefaultModel() != null && entity.getDefaultModel() == 1,
                entity.getCapabilityJson(),
                entity.getDefaultParamsJson(),
                entity.getRateLimitPerMinute(),
                entity.getConcurrencyLimit(),
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

    private String normalizeRequired(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(40000, fieldName + "不能为空");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
