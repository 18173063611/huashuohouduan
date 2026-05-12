package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.service.AdminTaskAdminService;
import com.huashuo.admin.vo.AdminTaskItem;
import com.huashuo.common.response.PageResult;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class AdminTaskAdminServiceImpl implements AdminTaskAdminService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final TaskMapper taskMapper;

    public AdminTaskAdminServiceImpl(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public PageResult<AdminTaskItem> listTasks(Long ownerUserId, String taskType, String status, String modelCode,
                                               Integer pageNo, Integer pageSize) {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        if (ownerUserId != null) {
            wrapper.eq(TaskEntity::getOwnerUserId, ownerUserId);
        }
        if (StringUtils.hasText(taskType)) {
            wrapper.eq(TaskEntity::getTaskType, taskType.trim().toUpperCase());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(TaskEntity::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(modelCode)) {
            wrapper.eq(TaskEntity::getModelCode, modelCode.trim());
        }
        long total = taskMapper.selectCount(wrapper);
        wrapper.orderByDesc(TaskEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminTaskItem> records = taskMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    private AdminTaskItem toItem(TaskEntity entity) {
        return new AdminTaskItem(
                entity.getTaskId(),
                entity.getOwnerUserId(),
                entity.getTaskType(),
                entity.getStatus(),
                entity.getProgress(),
                entity.getModelCode(),
                entity.getCreditCost(),
                entity.getCreditLogId(),
                entity.getQueueName(),
                entity.getMessageId(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }
}
