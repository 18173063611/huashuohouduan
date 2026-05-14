package com.huashuo.user.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.config.LoginAuthInterceptor;
import com.huashuo.user.service.AccountCreditDetailService;
import com.huashuo.user.vo.account.AccountCreditLogRecentRow;
import com.huashuo.user.vo.account.TaskCreditDetailResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户账户扩展：积分消费明细、单任务积分拆解（只读，不影响扣费逻辑）。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountCreditDetailService accountCreditDetailService;

    public AccountController(AccountCreditDetailService accountCreditDetailService) {
        this.accountCreditDetailService = accountCreditDetailService;
    }

    @GetMapping("/credit-log-recent")
    public ApiResponse<List<AccountCreditLogRecentRow>> creditLogRecent(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "20") Integer limit
    ) {
        long userId = currentUserId(request);
        return ApiResponse.success(accountCreditDetailService.listRecentCreditLogs(userId, limit), traceId());
    }

    @GetMapping("/task-credit-detail/{taskId}")
    public ApiResponse<TaskCreditDetailResponse> taskCreditDetail(
            HttpServletRequest request,
            @PathVariable long taskId
    ) {
        long userId = currentUserId(request);
        return ApiResponse.success(accountCreditDetailService.getTaskCreditDetail(userId, taskId), traceId());
    }

    private static long currentUserId(HttpServletRequest request) {
        Object v = request.getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        if (!(v instanceof Number n)) {
            throw new BusinessException(40100, "请先登录");
        }
        return n.longValue();
    }

    private static String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
