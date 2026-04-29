package com.huashuo.script.storyboard.dto;

public record StoryboardShotDto(
        int index,
        String visual,
        String narration,
        double estDurationSec
) {
}
