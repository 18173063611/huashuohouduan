package com.huashuo.admin.service;

import com.huashuo.admin.dto.AdminModelSaveRequest;
import com.huashuo.admin.vo.AdminModelItem;
import com.huashuo.common.response.PageResult;

public interface AdminModelService {

    PageResult<AdminModelItem> listModels(String modelType, String provider, Boolean enabled,
                                          Integer pageNo, Integer pageSize);

    AdminModelItem saveModel(AdminModelSaveRequest request, AdminOperationContext context);

    AdminModelItem updateModel(Long modelId, AdminModelSaveRequest request, AdminOperationContext context);

    AdminModelItem setEnabled(Long modelId, boolean enabled, AdminOperationContext context);

    AdminModelItem setDefault(Long modelId, AdminOperationContext context);
}
