package com.huashuo.video.service;

import com.huashuo.video.dto.VideoParseQueryResponse;
import com.huashuo.video.dto.VideoParseRequest;
import com.huashuo.video.dto.VideoParseSubmitResponse;

public interface VideoParseService {

    VideoParseSubmitResponse submit(VideoParseRequest request, String traceId);

    VideoParseQueryResponse getParseResult(Long taskId);
}
