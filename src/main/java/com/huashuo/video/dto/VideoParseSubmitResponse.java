package com.huashuo.video.dto;

public record VideoParseSubmitResponse(
        Long taskId,
        String status,
        VideoParseResultDto mockParseResult
) {
}
