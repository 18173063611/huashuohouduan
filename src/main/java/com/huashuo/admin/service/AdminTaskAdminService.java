package com.huashuo.admin.service;

import com.huashuo.admin.dto.AdminTaskManualRetryRequest;
import com.huashuo.admin.dto.AdminTaskManualRetryApplyRequest;
import com.huashuo.admin.dto.AdminTaskProviderOpsUpdateRequest;
import com.huashuo.admin.vo.AdminTaskItem;
import com.huashuo.common.response.PageResult;

public interface AdminTaskAdminService {

    PageResult<AdminTaskItem> listTasks(Long ownerUserId, String taskType, String status, String modelCode,
                                        Boolean providerOpsOnly, String providerOpsStatus,
                                        Integer pageNo, Integer pageSize);

    AdminTaskItem updateProviderOps(Long taskId, AdminTaskProviderOpsUpdateRequest request,
                                    AdminOperationContext context);

    AdminTaskItem requestManualRetry(Long taskId, AdminTaskManualRetryApplyRequest request,
                                     AdminOperationContext context);

    AdminTaskItem manualRetry(Long taskId, AdminTaskManualRetryRequest request, AdminOperationContext context);
}
