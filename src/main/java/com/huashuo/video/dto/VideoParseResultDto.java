package com.huashuo.video.dto;

import java.util.List;

public record VideoParseResultDto(
        String videoUrl,
        double durationSeconds,
        String summary,
        List<VideoParseSceneDto> scenes
) {
}
