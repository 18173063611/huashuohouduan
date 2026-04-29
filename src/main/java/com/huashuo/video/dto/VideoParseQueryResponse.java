package com.huashuo.video.dto;

public record VideoParseQueryResponse(
        Long taskId,
        String status,
        VideoParseResultDto parseResult
) {
}
