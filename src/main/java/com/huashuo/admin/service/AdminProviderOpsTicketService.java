package com.huashuo.admin.service;

import com.huashuo.admin.dto.AdminTaskManualRetryApplyRequest;
import com.huashuo.admin.dto.AdminTaskManualRetryRequest;
import com.huashuo.admin.dto.AdminTaskProviderOpsUpdateRequest;
import com.huashuo.admin.vo.AdminProviderOpsTicketItem;
import com.huashuo.common.response.PageResult;

public interface AdminProviderOpsTicketService {

    PageResult<AdminProviderOpsTicketItem> listTickets(String status,
                                                       String priority,
                                                       Long assigneeAdminId,
                                                       Boolean overdueOnly,
                                                       String supplierTicketId,
                                                       String providerTaskId,
                                                       String taskType,
                                                       String retryApprovalStatus,
                                                       Integer pageNo,
                                                       Integer pageSize);

    AdminProviderOpsTicketItem updateTicket(Long ticketId,
                                            AdminTaskProviderOpsUpdateRequest request,
                                            AdminOperationContext context);

    AdminProviderOpsTicketItem requestManualRetry(Long ticketId,
                                                  AdminTaskManualRetryApplyRequest request,
                                                  AdminOperationContext context);

    AdminProviderOpsTicketItem reviewManualRetry(Long ticketId,
                                                 AdminTaskManualRetryRequest request,
                                                 AdminOperationContext context);
}
