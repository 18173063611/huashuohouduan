package com.huashuo.admin.service;

public interface AdminOperationAuditService {

    void record(AdminOperationContext context, String operationType, String targetType, Long targetId,
                Object beforeSnapshot, Object afterSnapshot);
}
