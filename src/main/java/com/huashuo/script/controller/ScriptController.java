package com.huashuo.script.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.script.dto.RewriteScriptRequest;
import com.huashuo.script.dto.RewriteScriptResponse;
import com.huashuo.script.service.ScriptService;
import com.huashuo.script.vo.ScriptVersionItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
/**
 * 文案改写接口：根据原始文案生成 mock 改写版本，并按项目查询历史脚本版本。
 */
@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptController {

    private final ScriptService scriptService;

    public ScriptController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @PostMapping("/rewrite")
    public ApiResponse<RewriteScriptResponse> rewrite(@Valid @RequestBody RewriteScriptRequest request) {
        return ApiResponse.success(scriptService.rewrite(request, traceId()), traceId());
    }

    @GetMapping("/{projectId}")
    public ApiResponse<List<ScriptVersionItem>> listByProject(@PathVariable @NotNull Long projectId) {
        return ApiResponse.success(scriptService.listProjectScripts(projectId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
