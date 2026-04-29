package com.huashuo.script.storyboard.dto;

import java.util.List;

public record StoryboardGenerateResponse(
        Long taskId,
        String status,
        List<StoryboardShotDto> storyboard
) {
}
