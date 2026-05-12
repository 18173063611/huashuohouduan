package com.huashuo.admin.service;

import com.huashuo.admin.vo.AdminTaskItem;
import com.huashuo.common.response.PageResult;

public interface AdminTaskAdminService {

    PageResult<AdminTaskItem> listTasks(Long ownerUserId, String taskType, String status, String modelCode,
                                        Integer pageNo, Integer pageSize);
}
