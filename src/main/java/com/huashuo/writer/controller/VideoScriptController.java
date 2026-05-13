package com.huashuo.writer.controller;


import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.writer.VO.ScriptVO;
import com.huashuo.writer.service.VideoScriptService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalLong;

/**
 * 视频理解接口：调用方舟 doubao-seed-2-0-lite 多模态模型对单条视频做分镜解析。
 *
 * <p>每次请求都会通过 {@link VideoScriptService} 创建一条 task_type={@code VIDEO_PARSE} 的本地任务，
 * 并按 {@code ai_billing_step_config} 配置预扣积分；老接口契约保持不变（仅在表单/请求头新增可选 projectId 与
 * Authorization / Idempotency-Key），匿名调用不扣费。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/video/script")
@Slf4j
public class VideoScriptController {

    @Autowired
    private VideoScriptService videoScriptService;

    @Autowired
    private UserAuthService userAuthService;

    @PostMapping("/analy")
    public ApiResponse<List<ScriptVO>> scriptAnalyze(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        Long ownerUserId = resolveOwner(authorization, xAuthToken);
        return ApiResponse.success(
                videoScriptService.scriptAnalyze(url, ownerUserId, projectId, traceId(),
                        trimIdempotency(idempotencyHeader)),
                traceId());
    }

    @PostMapping("/url")
    public ApiResponse<List<ScriptVO>> scriptUrl(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestParam String url,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        Long ownerUserId = resolveOwner(authorization, xAuthToken);
        return ApiResponse.success(
                videoScriptService.scriptAnalyzeByUrl(url, ownerUserId, projectId, traceId(),
                        trimIdempotency(idempotencyHeader)),
                traceId());
    }

    private Long resolveOwner(String authorization, String xAuthToken) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        return viewer.isPresent() ? viewer.getAsLong() : null;
    }

    private String trimIdempotency(String idempotencyHeader) {
        return StringUtils.hasText(idempotencyHeader) ? idempotencyHeader.trim() : null;
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
