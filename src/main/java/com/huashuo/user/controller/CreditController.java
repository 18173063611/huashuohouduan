package com.huashuo.user.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.service.CreditService;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.user.vo.TaskCreditQuoteResponse;
import com.huashuo.user.vo.UserCreditMeResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧积分查询：当前账户、任务类型预计消耗（与配置一致）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/credits")
public class CreditController {

    private final UserAuthService userAuthService;
    private final CreditService creditService;
    private final BillingEstimateService billingEstimateService;

    public CreditController(UserAuthService userAuthService, CreditService creditService,
                            BillingEstimateService billingEstimateService) {
        this.userAuthService = userAuthService;
        this.creditService = creditService;
        this.billingEstimateService = billingEstimateService;
    }

    @GetMapping("/me")
    public ApiResponse<UserCreditMeResponse> creditsMe(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken) {
        long userId = userAuthService.requireUserId(authorization, xAuthToken);
        UserCreditAccountEntity account = creditService.ensureAccount(userId);
        return ApiResponse.success(
                new UserCreditMeResponse(
                        userId,
                        safe(account.getBalance()),
                        safe(account.getFrozenBalance()),
                        safe(account.getTotalRecharged()),
                        safe(account.getTotalConsumed())
                ),
                traceId()
        );
    }

    /**
     * 预计消耗：无需登录，供前端展示「预计扣费」。
     * <p>已迁移到 {@link BillingEstimateService}，与 {@code TaskService.createTask} 的实际预扣金额严格一致；
     * 推荐前端改用 {@code GET /api/v1/billing/estimate}，本接口保留为向下兼容入口。</p>
     */
    @GetMapping("/task-quote")
    public ApiResponse<TaskCreditQuoteResponse> taskQuote(@RequestParam("taskType") String taskType) {
        if (!StringUtils.hasText(taskType)) {
            throw new BusinessException(40000, "taskType 不能为空");
        }
        BillingEstimateResponse resp = billingEstimateService.estimate(new BillingEstimateRequest(
                taskType, null, null, null, null, null, null, null));
        return ApiResponse.success(
                new TaskCreditQuoteResponse(resp.taskType(), resp.estimatedCreditCost(), resp.modelCode()),
                traceId()
        );
    }

    private static long safe(Long v) {
        return v == null ? 0L : v;
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
