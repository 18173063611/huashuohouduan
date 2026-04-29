package com.huashuo.video.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.video.dto.VideoParseQueryResponse;
import com.huashuo.video.dto.VideoParseRequest;
import com.huashuo.video.dto.VideoParseSubmitResponse;
import com.huashuo.video.service.VideoParseService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短视频链接解析入口；路径与《后端文件代码开发规范》§7 {@code POST /api/v1/video-sources/parse} 一致。
 */
@Validated
/**
 * 视频解析接口：提交视频解析占位任务，并按 taskId 查询 mock 解析结果。
 */
@RestController
@RequestMapping("/api/v1/video-sources")
public class VideoParseController {

    private final VideoParseService videoParseService;

    public VideoParseController(VideoParseService videoParseService) {
        this.videoParseService = videoParseService;
    }

    @PostMapping("/parse")
    public ApiResponse<VideoParseSubmitResponse> parse(@Valid @RequestBody VideoParseRequest request) {
        return ApiResponse.success(videoParseService.submit(request, traceId()), traceId());
    }

    @GetMapping("/parse/{taskId}")
    public ApiResponse<VideoParseQueryResponse> getParse(@PathVariable Long taskId) {
        return ApiResponse.success(videoParseService.getParseResult(taskId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
