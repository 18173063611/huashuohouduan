package com.huashuo.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.billing.entity.AiBillingStepConfigEntity;
import com.huashuo.billing.mapper.AiBillingStepConfigMapper;
import com.huashuo.billing.service.BillingStepConfigService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

@Service
public class BillingStepConfigServiceImpl implements BillingStepConfigService {

    private final AiBillingStepConfigMapper aiBillingStepConfigMapper;

    public BillingStepConfigServiceImpl(AiBillingStepConfigMapper aiBillingStepConfigMapper) {
        this.aiBillingStepConfigMapper = aiBillingStepConfigMapper;
    }

    @Override
    public List<AiBillingStepConfigEntity> listEnabledSteps(String taskType) {
        if (!StringUtils.hasText(taskType)) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<AiBillingStepConfigEntity> w = new LambdaQueryWrapper<>();
        w.eq(AiBillingStepConfigEntity::getTaskType, taskType.trim().toUpperCase())
                .eq(AiBillingStepConfigEntity::getEnabled, 1)
                .eq(AiBillingStepConfigEntity::getDeleted, 0)
                .orderByAsc(AiBillingStepConfigEntity::getSortOrder)
                .orderByAsc(AiBillingStepConfigEntity::getStepId);
        return aiBillingStepConfigMapper.selectList(w);
    }

    @Override
    public OptionalLong aggregateCreditCost(String taskType) {
        List<AiBillingStepConfigEntity> steps = listEnabledSteps(taskType);
        if (steps.isEmpty()) {
            return OptionalLong.empty();
        }
        long total = 0L;
        for (AiBillingStepConfigEntity step : steps) {
            Long cost = step.getCreditCost();
            if (cost != null && cost > 0) {
                total += cost;
            }
        }
        return OptionalLong.of(Math.max(0L, total));
    }
}
