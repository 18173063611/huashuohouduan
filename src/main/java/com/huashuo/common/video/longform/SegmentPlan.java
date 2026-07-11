package com.huashuo.common.video.longform;

import java.util.List;

public record SegmentPlan(
        int segmentIndex,
        int globalStartSeconds,
        int globalEndSeconds,
        int durationSeconds,
        List<StoryboardScene> scenes,
        String cutReason
) {
}
