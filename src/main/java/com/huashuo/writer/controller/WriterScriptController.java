package com.huashuo.writer.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.writer.dto.ApplyWriterScriptRequest;
import com.huashuo.writer.dto.UpdateWriterScriptRequest;
import com.huashuo.writer.service.WriterScriptService;
import com.huashuo.writer.vo.WriterScriptItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
/**
 * 文案改写页面脚本接口：不包含 /writer/douyin/parse，只负责保存和回显最终文案。
 */
@RestController
@RequestMapping("/api/v1/writer/scripts")
public class WriterScriptController {

    private final WriterScriptService writerScriptService;

    public WriterScriptController(WriterScriptService writerScriptService) {
        this.writerScriptService = writerScriptService;
    }

    @PostMapping("/apply")
    public ApiResponse<WriterScriptItem> apply(@Valid @RequestBody ApplyWriterScriptRequest request) {
        return ApiResponse.success(writerScriptService.apply(request), traceId());
    }

    @GetMapping("/current")
    public ApiResponse<WriterScriptItem> current(@RequestParam @NotNull Long projectId) {
        return ApiResponse.success(writerScriptService.getCurrent(projectId), traceId());
    }

    @PutMapping("/{scriptId}")
    public ApiResponse<WriterScriptItem> update(
            @PathVariable @NotNull Long scriptId,
            @Valid @RequestBody UpdateWriterScriptRequest request
    ) {
        return ApiResponse.success(writerScriptService.update(scriptId, request), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
