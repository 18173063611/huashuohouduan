package com.huashuo.admin.service;

import com.huashuo.admin.dto.AdminCreditAdjustRequest;
import com.huashuo.admin.dto.AdminPasswordResetRequest;
import com.huashuo.admin.dto.AdminUserCreateRequest;
import com.huashuo.admin.dto.AdminUserUpdateRequest;
import com.huashuo.admin.vo.AdminCreditAccountResponse;
import com.huashuo.admin.vo.AdminCreditLogItem;
import com.huashuo.admin.vo.AdminUserItem;
import com.huashuo.common.response.PageResult;

public interface AdminUserService {

    PageResult<AdminUserItem> listUsers(String keyword, String role, String status, Integer pageNo, Integer pageSize);

    AdminUserItem getUser(Long userId);

    AdminUserItem createUser(AdminUserCreateRequest request, AdminOperationContext context);

    AdminUserItem updateUser(Long userId, AdminUserUpdateRequest request, AdminOperationContext context);

    void deleteUser(Long userId, AdminOperationContext context);

    AdminUserItem enableUser(Long userId, AdminOperationContext context);

    AdminUserItem disableUser(Long userId, AdminOperationContext context);

    void resetPassword(Long userId, AdminPasswordResetRequest request, AdminOperationContext context);

    AdminCreditAccountResponse getCreditAccount(Long userId);

    AdminCreditAccountResponse adjustCredits(Long userId, AdminCreditAdjustRequest request, AdminOperationContext context);

    PageResult<AdminCreditLogItem> listCreditLogs(Long userId, Integer pageNo, Integer pageSize);
}
