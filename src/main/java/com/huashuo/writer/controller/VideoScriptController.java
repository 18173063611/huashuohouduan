package com.huashuo.writer.controller;


import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.writer.VO.ScriptVO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.service.VideoScriptService;
import com.huashuo.writer.service.WriterService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/video/script")
@Slf4j
public class VideoScriptController {

    @Autowired
    private VideoScriptService videoScriptService;
    @Autowired
    private WriterService writerService;

    @PostMapping("/analy")
    public ApiResponse<List<ScriptVO>> scriptAnalyze(@RequestParam String url){
        return ApiResponse.success(videoScriptService.scriptAnalyze(url),traceId());
    }

    @PostMapping("/url")
    public ApiResponse<List<ScriptVO>> scriptUrl(@RequestParam String url){
        return ApiResponse.success(videoScriptService.scriptAnalyzeByUrl(url),traceId());
    }


    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
