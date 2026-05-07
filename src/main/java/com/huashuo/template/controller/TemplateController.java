package com.huashuo.template.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.template.service.TemplateService;
import com.huashuo.template.vo.TemplateCreateRequest;
import com.huashuo.template.vo.TemplateItem;
import com.huashuo.user.service.UserAuthService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalLong;

@Validated
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final UserAuthService userAuthService;

    public TemplateController(TemplateService templateService, UserAuthService userAuthService) {
        this.templateService = templateService;
        this.userAuthService = userAuthService;
    }

    @GetMapping
    public ApiResponse<List<TemplateItem>> listTemplates(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String tag
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(templateService.listTemplates(viewer, scope, keyword, sort, tag), traceId());
    }

    @GetMapping("/{templateId}")
    public ApiResponse<TemplateItem> getTemplate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long templateId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(templateService.getTemplateForViewer(templateId, viewer), traceId());
    }

    @PostMapping
    public ApiResponse<TemplateItem> createTemplate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody TemplateCreateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(templateService.createTemplate(viewer, request), traceId());
    }

    @PostMapping("/{templateId}/publish")
    public ApiResponse<TemplateItem> publishTemplate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long templateId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(templateService.publishTemplate(templateId, viewer), traceId());
    }

    @PostMapping("/{templateId}/fork")
    public ApiResponse<TemplateItem> forkTemplate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @PathVariable Long templateId
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return ApiResponse.success(templateService.forkTemplate(templateId, viewer), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}

