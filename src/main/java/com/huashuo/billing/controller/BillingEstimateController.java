package com.huashuo.billing.controller;

import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.service.UserAuthService;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.OptionalLong;

/**
 * 统一的"预计消耗"对外接口：前端在所有业务页面调用本接口拿展示金额 + 步骤明细 + 当前余额。
 *
 * <p>计算路径与 {@link com.huashuo.task.service.TaskService#createTask} 完全一致，保证
 * "页面显示金额 == 任务中心预扣金额"。如果客户端携带了 {@code Authorization / X-Auth-Token}，
 * 还会回填 {@code balance} 与 {@code enoughBalance}，便于前端在提交前禁用按钮。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/billing")
public class BillingEstimateController {

    private final BillingEstimateService billingEstimateService;
    private final UserAuthService userAuthService;

    public BillingEstimateController(BillingEstimateService billingEstimateService,
                                     UserAuthService userAuthService) {
        this.billingEstimateService = billingEstimateService;
        this.userAuthService = userAuthService;
    }

    @GetMapping("/estimate")
    public ApiResponse<BillingEstimateResponse> estimate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam("taskType") String taskType,
            @RequestParam(value = "modelCode", required = false) String modelCode,
            @RequestParam(value = "usageUnit", required = false) String usageUnit,
            @RequestParam(value = "inputTextLength", required = false) Integer inputTextLength,
            @RequestParam(value = "imageCount", required = false) Integer imageCount,
            @RequestParam(value = "durationSeconds", required = false) BigDecimal durationSeconds,
            @RequestParam(value = "providerCredits", required = false) BigDecimal providerCredits
    ) {
        if (!StringUtils.hasText(taskType)) {
            throw new BusinessException(40000, "taskType 不能为空");
        }
        Long ownerUserId = null;
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        if (viewer.isPresent()) {
            ownerUserId = viewer.getAsLong();
        }
        BillingEstimateResponse resp = billingEstimateService.estimate(new BillingEstimateRequest(
                taskType,
                modelCode,
                usageUnit,
                inputTextLength,
                imageCount,
                durationSeconds,
                providerCredits,
                ownerUserId
        ));
        return ApiResponse.success(resp, traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
