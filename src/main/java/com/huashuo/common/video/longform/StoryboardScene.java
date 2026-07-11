package com.huashuo.common.video.longform;

import java.util.List;

public record StoryboardScene(
        String sceneId,
        int startSeconds,
        int endSeconds,
        String visual,
        String action,
        String camera,
        List<DialogueCue> dialogues,
        List<String> referenceAssetIds
) {
    public int durationSeconds() {
        return Math.max(0, endSeconds - startSeconds);
    }
}
