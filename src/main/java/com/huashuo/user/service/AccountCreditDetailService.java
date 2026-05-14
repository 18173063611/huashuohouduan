package com.huashuo.user.service;

import com.huashuo.user.vo.account.AccountCreditLogRecentRow;
import com.huashuo.user.vo.account.TaskCreditDetailResponse;

import java.util.List;

public interface AccountCreditDetailService {

    List<AccountCreditLogRecentRow> listRecentCreditLogs(long userId, int limit);

    TaskCreditDetailResponse getTaskCreditDetail(long viewerUserId, long taskId);
}
