package com.huashuo.common.video.longform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class SmartSegmentPlanner {

    private static final int TARGET_SEGMENT_SECONDS = 12;

    public List<SegmentPlan> plan(List<StoryboardScene> scenes,
                                  int totalDurationSeconds,
                                  int minSegmentSeconds,
                                  int maxSegmentSeconds) {
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalArgumentException("scenes is required");
        }
        if (totalDurationSeconds <= 0) {
            throw new IllegalArgumentException("totalDurationSeconds must be positive");
        }
        if (minSegmentSeconds <= 0 || maxSegmentSeconds < minSegmentSeconds) {
            throw new IllegalArgumentException("invalid segment duration limits");
        }
        List<StoryboardScene> sortedScenes = scenes.stream()
                .sorted(Comparator.comparingInt(StoryboardScene::startSeconds))
                .toList();
        validateTimeline(sortedScenes, totalDurationSeconds);

        List<Integer> cutPoints = preferredCutPoints(sortedScenes, totalDurationSeconds);
        List<Integer> path = bestBoundaryPath(cutPoints, minSegmentSeconds, maxSegmentSeconds);
        if (path == null) {
            path = balancedFallback(totalDurationSeconds, minSegmentSeconds, maxSegmentSeconds);
        }
        return toSegments(path, sortedScenes, minSegmentSeconds, maxSegmentSeconds);
    }

    private void validateTimeline(List<StoryboardScene> scenes, int totalDurationSeconds) {
        int previousEnd = 0;
        for (StoryboardScene scene : scenes) {
            if (scene.startSeconds() < 0 || scene.endSeconds() <= scene.startSeconds()) {
                throw new IllegalArgumentException("scene timeline is invalid: " + scene.sceneId());
            }
            if (scene.startSeconds() < previousEnd) {
                throw new IllegalArgumentException("scenes overlap near " + scene.sceneId());
            }
            for (DialogueCue cue : safeDialogues(scene)) {
                int cueStart = cue.startSeconds() > 0 || cue.endSeconds() > 0 ? cue.startSeconds() : scene.startSeconds();
                int cueEnd = cue.endSeconds() > 0 ? cue.endSeconds() : scene.endSeconds();
                if (cueStart < scene.startSeconds() || cueEnd > scene.endSeconds() || cueEnd <= cueStart) {
                    throw new IllegalArgumentException("dialogue timeline is invalid in " + scene.sceneId());
                }
            }
            previousEnd = scene.endSeconds();
        }
        if (previousEnd != totalDurationSeconds) {
            throw new IllegalArgumentException("scene timeline must end at totalDurationSeconds");
        }
    }

    private List<Integer> preferredCutPoints(List<StoryboardScene> scenes, int totalDurationSeconds) {
        LinkedHashSet<Integer> points = new LinkedHashSet<>();
        points.add(0);
        for (StoryboardScene scene : scenes) {
            points.add(scene.endSeconds());
        }
        points.add(totalDurationSeconds);
        return points.stream().sorted().toList();
    }

    private List<Integer> bestBoundaryPath(List<Integer> cutPoints, int minSegmentSeconds, int maxSegmentSeconds) {
        Map<Integer, Score> best = new HashMap<>();
        Map<Integer, Integer> previous = new HashMap<>();
        best.put(0, new Score(0, 0, 0));
        for (int i = 1; i < cutPoints.size(); i++) {
            int end = cutPoints.get(i);
            for (int j = 0; j < i; j++) {
                int start = cutPoints.get(j);
                int duration = end - start;
                if (duration < minSegmentSeconds || duration > maxSegmentSeconds) {
                    continue;
                }
                Score prefix = best.get(start);
                if (prefix == null) {
                    continue;
                }
                Score candidate = prefix.add(duration);
                Score current = best.get(end);
                if (current == null || candidate.compareTo(current) < 0) {
                    best.put(end, candidate);
                    previous.put(end, start);
                }
            }
        }
        int total = cutPoints.get(cutPoints.size() - 1);
        if (!best.containsKey(total)) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        int cursor = total;
        path.add(cursor);
        while (cursor != 0) {
            Integer prev = previous.get(cursor);
            if (prev == null) {
                return null;
            }
            cursor = prev;
            path.add(0, cursor);
        }
        return path;
    }

    private List<Integer> balancedFallback(int totalDurationSeconds, int minSegmentSeconds, int maxSegmentSeconds) {
        int count = (int) Math.ceil((double) totalDurationSeconds / maxSegmentSeconds);
        while (count > 1 && totalDurationSeconds / count < minSegmentSeconds) {
            count--;
        }
        if (count <= 0) {
            count = 1;
        }
        List<Integer> path = new ArrayList<>();
        path.add(0);
        int cursor = 0;
        for (int i = 1; i <= count; i++) {
            int remaining = totalDurationSeconds - cursor;
            int remainingSegments = count - i + 1;
            int next = cursor + Math.max(minSegmentSeconds,
                    Math.min(maxSegmentSeconds, (int) Math.ceil((double) remaining / remainingSegments)));
            if (i == count) {
                next = totalDurationSeconds;
            }
            path.add(next);
            cursor = next;
        }
        return path;
    }

    private List<SegmentPlan> toSegments(List<Integer> path,
                                         List<StoryboardScene> scenes,
                                         int minSegmentSeconds,
                                         int maxSegmentSeconds) {
        List<SegmentPlan> segments = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            int start = path.get(i);
            int end = path.get(i + 1);
            int duration = end - start;
            if (duration < minSegmentSeconds || duration > maxSegmentSeconds) {
                throw new IllegalArgumentException("segment duration out of range: " + duration);
            }
            List<StoryboardScene> included = scenes.stream()
                    .filter(scene -> scene.startSeconds() >= start && scene.endSeconds() <= end)
                    .toList();
            if (included.isEmpty()) {
                throw new IllegalArgumentException("segment has no complete scenes: " + start + "-" + end);
            }
            String reason = "scene boundary and subtitle boundary";
            segments.add(new SegmentPlan(i + 1, start, end, duration, included, reason));
        }
        return segments;
    }

    private static List<DialogueCue> safeDialogues(StoryboardScene scene) {
        return scene.dialogues() == null ? List.of() : scene.dialogues();
    }

    private record Score(int segmentCount, int deviation, int shortPenalty) implements Comparable<Score> {
        Score add(int duration) {
            int shortPenaltyValue = duration < 8 ? (8 - duration) * 3 : 0;
            return new Score(segmentCount + 1,
                    deviation + Math.abs(TARGET_SEGMENT_SECONDS - duration),
                    shortPenalty + shortPenaltyValue);
        }

        @Override
        public int compareTo(Score other) {
            int byCount = Integer.compare(segmentCount, other.segmentCount);
            if (byCount != 0) return byCount;
            int byShortPenalty = Integer.compare(shortPenalty, other.shortPenalty);
            if (byShortPenalty != 0) return byShortPenalty;
            return Integer.compare(deviation, other.deviation);
        }
    }
}
