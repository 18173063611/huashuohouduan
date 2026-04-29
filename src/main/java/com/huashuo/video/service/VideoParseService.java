package com.huashuo.video.service;

import com.huashuo.video.dto.VideoParseQueryResponse;
import com.huashuo.video.dto.VideoParseRequest;
import com.huashuo.video.dto.VideoParseSubmitResponse;

/**
 * 视频解析服务接口：定义视频解析任务提交与结果查询能力。
 */
public interface VideoParseService {

    VideoParseSubmitResponse submit(VideoParseRequest request, String traceId);

    VideoParseQueryResponse getParseResult(Long taskId);
}
