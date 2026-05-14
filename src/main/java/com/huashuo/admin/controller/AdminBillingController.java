package com.huashuo.admin.controller;

import com.huashuo.admin.dto.AdminBillingStepSaveRequest;
import com.huashuo.admin.dto.AdminModelPriceSaveRequest;
import com.huashuo.admin.service.AdminBillingReportService;
import com.huashuo.admin.service.AdminBillingService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.vo.AdminBillingStepItem;
import com.huashuo.admin.vo.AdminModelPriceItem;
import com.huashuo.admin.vo.AdminUsageSummaryResponse;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.config.LoginAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 后台 AI 计费配置管理：路由前缀 /api/v1/admin/billing/**，由 {@code AdminAuthInterceptor} 强制校验管理员身份。
 *
 * <p>本控制器仅修改 ai_billing_step_config / ai_model_price 两张配置表，不会调用 CreditService 或
 * CreditBillingService，因此不会即时影响进行中的任务结算，但下一次任务创建时 {@code BillingStepConfigService}
 * 会自动读取最新启用项进行积分汇总。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/billing")
public class AdminBillingController {

    private static final DateTimeFormatter DATE_FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AdminBillingService adminBillingService;
    private final AdminBillingReportService adminBillingReportService;

    public AdminBillingController(AdminBillingService adminBillingService,
                                  AdminBillingReportService adminBillingReportService) {
        this.adminBillingService = adminBillingService;
        this.adminBillingReportService = adminBillingReportService;
    }

    // ---------- ai_billing_step_config ----------

    @GetMapping("/steps")
    public ApiResponse<PageResult<AdminBillingStepItem>> listSteps(
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String functionModule,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.success(
                adminBillingService.listSteps(taskType, functionModule, enabled, pageNo, pageSize),
                traceId()
        );
    }

    @PostMapping("/steps")
    public ApiResponse<AdminBillingStepItem> createStep(@Valid @RequestBody AdminBillingStepSaveRequest request,
                                                       HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.createStep(request, operationContext(servletRequest)),
                traceId()
        );
    }

    @PutMapping("/steps/{stepId}")
    public ApiResponse<AdminBillingStepItem> updateStep(@PathVariable Long stepId,
                                                        @Valid @RequestBody AdminBillingStepSaveRequest request,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.updateStep(stepId, request, operationContext(servletRequest)),
                traceId()
        );
    }

    @PostMapping("/steps/{stepId}/enable")
    public ApiResponse<AdminBillingStepItem> enableStep(@PathVariable Long stepId,
                                                       HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.setStepEnabled(stepId, true, operationContext(servletRequest)),
                traceId()
        );
    }

    @PostMapping("/steps/{stepId}/disable")
    public ApiResponse<AdminBillingStepItem> disableStep(@PathVariable Long stepId,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.setStepEnabled(stepId, false, operationContext(servletRequest)),
                traceId()
        );
    }

    // ---------- ai_model_price ----------

    @GetMapping("/prices")
    public ApiResponse<PageResult<AdminModelPriceItem>> listPrices(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.success(
                adminBillingService.listPrices(provider, taskType, enabled, pageNo, pageSize),
                traceId()
        );
    }

    @PostMapping("/prices")
    public ApiResponse<AdminModelPriceItem> createPrice(@Valid @RequestBody AdminModelPriceSaveRequest request,
                                                       HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.createPrice(request, operationContext(servletRequest)),
                traceId()
        );
    }

    @PutMapping("/prices/{priceId}")
    public ApiResponse<AdminModelPriceItem> updatePrice(@PathVariable Long priceId,
                                                        @Valid @RequestBody AdminModelPriceSaveRequest request,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.updatePrice(priceId, request, operationContext(servletRequest)),
                traceId()
        );
    }

    @PostMapping("/prices/{priceId}/enable")
    public ApiResponse<AdminModelPriceItem> enablePrice(@PathVariable Long priceId,
                                                       HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.setPriceEnabled(priceId, true, operationContext(servletRequest)),
                traceId()
        );
    }

    @PostMapping("/prices/{priceId}/disable")
    public ApiResponse<AdminModelPriceItem> disablePrice(@PathVariable Long priceId,
                                                        HttpServletRequest servletRequest) {
        return ApiResponse.success(
                adminBillingService.setPriceEnabled(priceId, false, operationContext(servletRequest)),
                traceId()
        );
    }

    // ---------- AI 用量与积分成本统计报表 ----------

    /**
     * JSON 形式返回报表。format=csv 时切换到 {@link #exportUsageSummaryCsv}（也可直接命中 {@code .csv} 后缀）。
     */
    @GetMapping("/usage-summary")
    public Object usageSummary(
            @RequestParam(required = false, defaultValue = "DATE") String dimension,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String functionModule,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String usageUnit,
            @RequestParam(required = false) String format
    ) {
        AdminBillingReportService.Dimension parsedDimension = parseDimension(dimension);
        if ("csv".equalsIgnoreCase(format)) {
            return buildCsvResponse(parsedDimension, from, to, taskType, functionModule, provider, modelCode, usageUnit);
        }
        AdminUsageSummaryResponse summary = adminBillingReportService.usageSummary(
                parsedDimension, from, to, taskType, functionModule, provider, modelCode, usageUnit);
        return ApiResponse.success(summary, traceId());
    }

    @GetMapping(value = "/usage-summary.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportUsageSummaryCsv(
            @RequestParam(required = false, defaultValue = "DATE") String dimension,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String functionModule,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String usageUnit
    ) {
        return buildCsvResponse(parseDimension(dimension), from, to, taskType, functionModule,
                provider, modelCode, usageUnit);
    }

    private ResponseEntity<byte[]> buildCsvResponse(AdminBillingReportService.Dimension dimension,
                                                    LocalDate from, LocalDate to,
                                                    String taskType, String functionModule, String provider,
                                                    String modelCode, String usageUnit) {
        String csv = adminBillingReportService.exportUsageSummaryCsv(dimension, from, to, taskType, functionModule,
                provider, modelCode, usageUnit);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        String fileName = "ai-usage-summary-" + dimension.name().toLowerCase() + "-"
                + (from == null ? "" : from.format(DATE_FILE_FORMATTER)) + "-"
                + (to == null ? "" : to.format(DATE_FILE_FORMATTER)) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", fileName);
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private AdminBillingReportService.Dimension parseDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return AdminBillingReportService.Dimension.DATE;
        }
        try {
            return AdminBillingReportService.Dimension.valueOf(dimension.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(40000, "dimension 仅支持 DATE / FUNCTION_MODULE / TASK_TYPE / PROVIDER / MODEL_CODE / USAGE_UNIT");
        }
    }

    private AdminOperationContext operationContext(HttpServletRequest request) {
        Object currentUserId = request.getAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE);
        Long adminUserId = currentUserId instanceof Long userId ? userId : null;
        return new AdminOperationContext(adminUserId, clientIp(request), traceId());
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
