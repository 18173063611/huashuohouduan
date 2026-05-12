package com.huashuo.admin.service;

import com.huashuo.admin.vo.AdminCreditLogItem;
import com.huashuo.common.response.PageResult;

public interface AdminCreditLogService {

    PageResult<AdminCreditLogItem> listLogs(Long userId, String changeType, Long relatedTaskId,
                                            Integer pageNo, Integer pageSize);
}
