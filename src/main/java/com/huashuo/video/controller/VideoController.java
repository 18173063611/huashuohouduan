package com.huashuo.video.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoVO;
import com.huashuo.video.service.VideoService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/video")
@Slf4j
public class VideoController {

    @Autowired
    private VideoService videoService;

    @PostMapping("/generate/text")
    public ApiResponse<VideoVO> generateText(@RequestBody TextDTO request) {
        return ApiResponse.success(videoService.generateText(request), traceId());
    }

    @PostMapping("/generate/image")
    public ApiResponse<VideoVO> generateImage(@RequestBody ImageDTO request) {
        return ApiResponse.success(videoService.generateImage(request), traceId());
    }


    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

}
