package com.huashuo.admin.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.admin.entity.AdminOperationLogEntity;
import com.huashuo.admin.mapper.AdminOperationLogMapper;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class AdminOperationAuditServiceImpl implements AdminOperationAuditService {

    private final AdminOperationLogMapper adminOperationLogMapper;
    private final ObjectMapper objectMapper;

    public AdminOperationAuditServiceImpl(AdminOperationLogMapper adminOperationLogMapper, ObjectMapper objectMapper) {
        this.adminOperationLogMapper = adminOperationLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AdminOperationContext context, String operationType, String targetType, Long targetId,
                       Object beforeSnapshot, Object afterSnapshot) {
        AdminOperationLogEntity entity = new AdminOperationLogEntity();
        entity.setAdminUserId(context == null ? null : context.adminUserId());
        entity.setOperationType(normalize(operationType));
        entity.setTargetType(normalize(targetType));
        entity.setTargetId(targetId);
        entity.setBeforeJson(toJson(beforeSnapshot));
        entity.setAfterJson(toJson(afterSnapshot));
        entity.setIp(context == null ? null : trimToNull(context.ip()));
        entity.setTraceId(context == null ? null : trimToNull(context.traceId()));
        entity.setCreatedAt(LocalDateTime.now());
        adminOperationLogMapper.insert(entity);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(40000, "operation audit field must not be blank");
        }
        return value.trim().toUpperCase();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "failed to serialize admin operation snapshot");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
