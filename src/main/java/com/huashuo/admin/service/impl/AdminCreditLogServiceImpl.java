package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.service.AdminCreditLogService;
import com.huashuo.admin.vo.AdminCreditLogItem;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.mapper.UserCreditLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class AdminCreditLogServiceImpl implements AdminCreditLogService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserCreditLogMapper userCreditLogMapper;

    public AdminCreditLogServiceImpl(UserCreditLogMapper userCreditLogMapper) {
        this.userCreditLogMapper = userCreditLogMapper;
    }

    @Override
    public PageResult<AdminCreditLogItem> listLogs(Long userId, String changeType, Long relatedTaskId,
                                                   Integer pageNo, Integer pageSize) {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<UserCreditLogEntity> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(UserCreditLogEntity::getUserId, userId);
        }
        if (StringUtils.hasText(changeType)) {
            wrapper.eq(UserCreditLogEntity::getChangeType, changeType.trim().toUpperCase());
        }
        if (relatedTaskId != null) {
            wrapper.eq(UserCreditLogEntity::getRelatedTaskId, relatedTaskId);
        }
        long total = userCreditLogMapper.selectCount(wrapper);
        wrapper.orderByDesc(UserCreditLogEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminCreditLogItem> records = userCreditLogMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    private AdminCreditLogItem toItem(UserCreditLogEntity entity) {
        return new AdminCreditLogItem(
                entity.getCreditLogId(),
                entity.getUserId(),
                entity.getChangeType(),
                entity.getChangeAmount(),
                entity.getBeforeBalance(),
                entity.getAfterBalance(),
                entity.getRelatedTaskId(),
                entity.getModelCode(),
                entity.getOperatorAdminId(),
                entity.getRemark(),
                entity.getCreatedAt()
        );
    }
}
