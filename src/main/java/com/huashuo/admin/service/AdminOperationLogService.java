package com.huashuo.admin.service;

import com.huashuo.admin.vo.AdminOperationLogItem;
import com.huashuo.common.response.PageResult;

public interface AdminOperationLogService {

    PageResult<AdminOperationLogItem> listLogs(Long adminUserId, String operationType, String targetType,
                                               Integer pageNo, Integer pageSize);
}
