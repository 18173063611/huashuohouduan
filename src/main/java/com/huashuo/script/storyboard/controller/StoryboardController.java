package com.huashuo.script.storyboard.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.script.storyboard.dto.StoryboardGenerateRequest;
import com.huashuo.script.storyboard.dto.StoryboardGenerateResponse;
import com.huashuo.script.storyboard.service.StoryboardService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
/**
 * 分镜生成接口：基于脚本版本生成 mock 分镜列表，为后续画面生成和视频合成预留入口。
 */
@RestController
@RequestMapping("/api/v1/storyboards")
public class StoryboardController {

    private final StoryboardService storyboardService;

    public StoryboardController(StoryboardService storyboardService) {
        this.storyboardService = storyboardService;
    }

    @PostMapping("/generate")
    public ApiResponse<StoryboardGenerateResponse> generate(@Valid @RequestBody StoryboardGenerateRequest request) {
        return ApiResponse.success(storyboardService.generate(request, traceId()), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
