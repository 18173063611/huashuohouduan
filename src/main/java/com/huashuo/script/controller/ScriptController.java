package com.huashuo.script.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.script.dto.RewriteScriptRequest;
import com.huashuo.script.dto.RewriteScriptResponse;
import com.huashuo.script.service.ScriptService;
import com.huashuo.script.vo.ScriptVersionItem;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalLong;

@Validated
/**
 * 文案改写接口：根据原始文案生成 mock 改写版本，并按项目查询历史脚本版本。
 */
@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptController {

    private final ScriptService scriptService;
    private final UserAuthService userAuthService;

    public ScriptController(ScriptService scriptService, UserAuthService userAuthService) {
        this.scriptService = scriptService;
        this.userAuthService = userAuthService;
    }

    @PostMapping("/rewrite")
    public ApiResponse<RewriteScriptResponse> rewrite(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody RewriteScriptRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(scriptService.rewrite(request, traceId(), ownerUserId), traceId());
    }

    @GetMapping
    public ApiResponse<List<ScriptVersionItem>> listByProject(@RequestParam(required = false) Long projectId) {
        return ApiResponse.success(scriptService.listProjectScripts(projectId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
