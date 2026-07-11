package com.huashuo.common.video.longform;

public record DialogueCue(
        String speakerId,
        String text,
        String subtitle,
        int startSeconds,
        int endSeconds
) {
}
