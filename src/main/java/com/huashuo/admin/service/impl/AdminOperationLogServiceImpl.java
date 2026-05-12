package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.entity.AdminOperationLogEntity;
import com.huashuo.admin.mapper.AdminOperationLogMapper;
import com.huashuo.admin.service.AdminOperationLogService;
import com.huashuo.admin.vo.AdminOperationLogItem;
import com.huashuo.common.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class AdminOperationLogServiceImpl implements AdminOperationLogService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminOperationLogMapper adminOperationLogMapper;

    public AdminOperationLogServiceImpl(AdminOperationLogMapper adminOperationLogMapper) {
        this.adminOperationLogMapper = adminOperationLogMapper;
    }

    @Override
    public PageResult<AdminOperationLogItem> listLogs(Long adminUserId, String operationType, String targetType,
                                                      Integer pageNo, Integer pageSize) {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<AdminOperationLogEntity> wrapper = new LambdaQueryWrapper<>();
        if (adminUserId != null) {
            wrapper.eq(AdminOperationLogEntity::getAdminUserId, adminUserId);
        }
        if (StringUtils.hasText(operationType)) {
            wrapper.eq(AdminOperationLogEntity::getOperationType, operationType.trim().toUpperCase());
        }
        if (StringUtils.hasText(targetType)) {
            wrapper.eq(AdminOperationLogEntity::getTargetType, targetType.trim().toUpperCase());
        }
        long total = adminOperationLogMapper.selectCount(wrapper);
        wrapper.orderByDesc(AdminOperationLogEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminOperationLogItem> records = adminOperationLogMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    private AdminOperationLogItem toItem(AdminOperationLogEntity entity) {
        return new AdminOperationLogItem(
                entity.getOperationId(),
                entity.getAdminUserId(),
                entity.getOperationType(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getBeforeJson(),
                entity.getAfterJson(),
                entity.getIp(),
                entity.getTraceId(),
                entity.getCreatedAt()
        );
    }
}
