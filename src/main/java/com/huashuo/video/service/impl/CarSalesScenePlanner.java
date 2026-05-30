package com.huashuo.video.service.impl;

import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将分镜镜头整理成实际送给视频模型的生成段落。
 */
final class CarSalesScenePlanner {

    private static final String SEEDANCE_2_MODEL = "ep-20260512233524-85r4g";
    private static final int MAX_SEGMENT_COUNT = 12;
    private static final int DEFAULT_SEGMENT_DURATION = 8;
    private static final int DEFAULT_UNTIMED_SCENE_DURATION = 5;

    private CarSalesScenePlanner() {
    }

    static int normalizeSegmentCount(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(MAX_SEGMENT_COUNT, value));
    }

    static int normalizeSegmentDuration(Integer value, String model) {
        if (value == null) {
            return DEFAULT_SEGMENT_DURATION;
        }
        return Math.max(4, Math.min(maxSegmentDuration(model), value));
    }

    static int maxSegmentDuration(String model) {
        return isSeedance2(model) ? 15 : 12;
    }

    static boolean isSeedance2(String model) {
        return StringUtils.hasText(model) && SEEDANCE_2_MODEL.equals(model.trim());
    }

    static List<CarSalesVideoDTO.Scene> compactScenes(List<CarSalesVideoDTO.Scene> source, String model) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int maxDuration = maxSegmentDuration(model);
        List<SceneWithDuration> usable = source.stream()
                .filter(CarSalesScenePlanner::hasSceneContent)
                .map(scene -> new SceneWithDuration(scene, durationForPlanning(scene, model)))
                .toList();
        if (usable.isEmpty()) {
            return List.of();
        }

        List<List<SceneWithDuration>> groups = new ArrayList<>();
        List<SceneWithDuration> current = new ArrayList<>();
        int currentDuration = 0;
        for (SceneWithDuration item : usable) {
            if (!current.isEmpty() && currentDuration + item.duration() > maxDuration) {
                groups.add(current);
                current = new ArrayList<>();
                currentDuration = 0;
            }
            current.add(item);
            currentDuration += item.duration();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        List<CarSalesVideoDTO.Scene> compacted = new ArrayList<>();
        for (int i = 0; i < groups.size() && i < MAX_SEGMENT_COUNT; i++) {
            compacted.add(mergeGroup(groups.get(i), i + 1, model));
        }
        return compacted;
    }

    private static boolean hasSceneContent(CarSalesVideoDTO.Scene scene) {
        return scene != null && (StringUtils.hasText(scene.getVisualPrompt())
                || StringUtils.hasText(scene.getPrompt())
                || StringUtils.hasText(scene.getTitle())
                || StringUtils.hasText(scene.getVoiceText()));
    }

    private static int durationForPlanning(CarSalesVideoDTO.Scene scene, String model) {
        Integer duration = scene == null ? null : scene.getDuration();
        if (duration == null || duration <= 0) {
            return Math.min(DEFAULT_UNTIMED_SCENE_DURATION, maxSegmentDuration(model));
        }
        return Math.max(1, Math.min(maxSegmentDuration(model), duration));
    }

    private static CarSalesVideoDTO.Scene mergeGroup(List<SceneWithDuration> group, int segmentIndex, String model) {
        CarSalesVideoDTO.Scene merged = new CarSalesVideoDTO.Scene();
        merged.setSegmentIndex(segmentIndex);
        if (group == null || group.isEmpty()) {
            merged.setDuration(normalizeSegmentDuration(null, model));
            return merged;
        }
        if (group.size() == 1) {
            CarSalesVideoDTO.Scene copied = copyScene(group.get(0).scene());
            copied.setSegmentIndex(segmentIndex);
            copied.setDuration(normalizeMergedDuration(group.get(0).duration(), model));
            return copied;
        }

        int duration = group.stream().mapToInt(SceneWithDuration::duration).sum();
        String range = originalSceneRange(group);
        merged.setTitle("连续段落 " + segmentIndex + "（原分镜 " + range + "）");
        merged.setVisualPrompt(buildMergedPrompt(group, range, duration));
        merged.setPrompt(merged.getVisualPrompt());
        merged.setVoiceText(joinVoiceText(group.stream()
                .map(item -> item.scene().getVoiceText())
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList()));
        merged.setImageUrls(mergeImageUrls(group));
        if (merged.getImageUrls() != null && !merged.getImageUrls().isEmpty()) {
            merged.setReferenceImage(merged.getImageUrls().get(0));
        }
        merged.setDuration(normalizeMergedDuration(duration, model));
        return merged;
    }

    private static CarSalesVideoDTO.Scene copyScene(CarSalesVideoDTO.Scene source) {
        CarSalesVideoDTO.Scene copy = new CarSalesVideoDTO.Scene();
        copy.setSegmentIndex(source.getSegmentIndex());
        copy.setTitle(source.getTitle());
        copy.setVisualPrompt(source.getVisualPrompt());
        copy.setPrompt(source.getPrompt());
        copy.setImageUrls(source.getImageUrls() == null ? null : new ArrayList<>(source.getImageUrls()));
        copy.setReferenceImage(source.getReferenceImage());
        copy.setVoiceText(source.getVoiceText());
        copy.setDuration(source.getDuration());
        return copy;
    }

    private static int normalizeMergedDuration(int duration, String model) {
        return Math.max(4, Math.min(maxSegmentDuration(model), Math.max(1, duration)));
    }

    private static String originalSceneRange(List<SceneWithDuration> group) {
        List<String> indexes = group.stream()
                .map(item -> item.scene().getSegmentIndex())
                .filter(index -> index != null && index > 0)
                .map(String::valueOf)
                .toList();
        if (indexes.isEmpty()) {
            return "1-" + group.size();
        }
        return String.join("、", indexes);
    }

    private static String buildMergedPrompt(List<SceneWithDuration> group, String range, int duration) {
        List<String> lines = new ArrayList<>();
        lines.add("连续生成段落，合并原分镜 " + range + "，总时长约 " + duration
                + " 秒；请在同一条视频里自然完成这些子镜头，保持同一车辆、场景、光线和运动方向一致，避免每个子镜头重置主体或形成明显硬切。");
        for (int i = 0; i < group.size(); i++) {
            CarSalesVideoDTO.Scene scene = group.get(i).scene();
            String title = StringUtils.hasText(scene.getTitle()) ? scene.getTitle().trim() : "子镜头 " + (i + 1);
            String visual = firstText(scene.getVisualPrompt(), scene.getPrompt());
            lines.add((i + 1) + ". " + title + "：" + visual);
        }
        return String.join("\n", lines);
    }

    private static List<String> mergeImageUrls(List<SceneWithDuration> group) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (SceneWithDuration item : group) {
            CarSalesVideoDTO.Scene scene = item.scene();
            if (scene.getImageUrls() != null) {
                scene.getImageUrls().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .forEach(urls::add);
            }
            if (StringUtils.hasText(scene.getReferenceImage())) {
                urls.add(scene.getReferenceImage().trim());
            }
        }
        return urls.isEmpty() ? null : new ArrayList<>(urls);
    }

    private static String joinVoiceText(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        String current = "";
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            current = joinSentenceLike(current, line.trim());
        }
        return StringUtils.hasText(current) ? current : null;
    }

    private static String joinSentenceLike(String left, String right) {
        if (!StringUtils.hasText(left)) {
            return right;
        }
        if (!StringUtils.hasText(right)) {
            return left;
        }
        char last = left.charAt(left.length() - 1);
        char first = right.charAt(0);
        char beforeLast = left.length() >= 2 ? left.charAt(left.length() - 2) : '\0';
        if (shouldInsertSpeechSpace(last, beforeLast, first)) {
            return left + " " + right;
        }
        if ("。！？!?；;，,、.".indexOf(last) >= 0) {
            return left + right;
        }
        return left + "。" + right;
    }

    private static boolean isAsciiWord(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '\'' || ch == '_' || ch == '-' || ch == '+';
    }

    private static boolean shouldInsertSpeechSpace(char last, char beforeLast, char first) {
        if (isAsciiWord(last) && isAsciiWord(first)) {
            return true;
        }
        if ((last == '.' || last == ',') && Character.isDigit(beforeLast) && Character.isDigit(first)) {
            return false;
        }
        return "。！？!?；;，,、.:：".indexOf(last) >= 0 && isAsciiWord(first);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record SceneWithDuration(CarSalesVideoDTO.Scene scene, int duration) {
    }
}
