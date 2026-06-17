package com.huashuo.support.service;

import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.common.response.PageResult;
import com.huashuo.support.dto.CustomerFeedbackAdminUpdateRequest;
import com.huashuo.support.dto.CustomerFeedbackCreateRequest;
import com.huashuo.support.vo.CustomerFeedbackItem;

public interface CustomerFeedbackService {

    CustomerFeedbackItem create(long ownerUserId, CustomerFeedbackCreateRequest request);

    PageResult<CustomerFeedbackItem> listMine(long ownerUserId, Integer pageNo, Integer pageSize);

    PageResult<CustomerFeedbackItem> listAdmin(Long ownerUserId, String category, String status, String priority,
                                               String keyword, Integer pageNo, Integer pageSize);

    CustomerFeedbackItem getAdmin(Long feedbackId);

    CustomerFeedbackItem updateAdmin(Long feedbackId, CustomerFeedbackAdminUpdateRequest request,
                                     AdminOperationContext context);
}
