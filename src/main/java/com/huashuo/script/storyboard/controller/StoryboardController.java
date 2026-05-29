package com.huashuo.script.storyboard.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.script.storyboard.dto.StoryboardGenerateRequest;
import com.huashuo.script.storyboard.dto.StoryboardGenerateResponse;
import com.huashuo.script.storyboard.service.StoryboardService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

@Validated
/**
 * 分镜生成接口：基于脚本版本生成结构化分镜列表，为后续画面生成和视频合成预留入口。
 */
@RestController
@RequestMapping("/api/v1/storyboards")
public class StoryboardController {

    private final StoryboardService storyboardService;
    private final UserAuthService userAuthService;

    public StoryboardController(StoryboardService storyboardService, UserAuthService userAuthService) {
        this.storyboardService = storyboardService;
        this.userAuthService = userAuthService;
    }

    @PostMapping("/generate")
    public ApiResponse<StoryboardGenerateResponse> generate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody StoryboardGenerateRequest request
    ) {
        OptionalLong viewer = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        Long ownerUserId = viewer.isPresent() ? viewer.getAsLong() : null;
        return ApiResponse.success(storyboardService.generate(request, traceId(), ownerUserId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
