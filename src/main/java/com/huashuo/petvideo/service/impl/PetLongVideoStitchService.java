package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class PetLongVideoStitchService {

    private final ObjectMapper objectMapper;
    private final String ffmpegPath;
    private final HttpClient httpClient;

    public PetLongVideoStitchService(ObjectMapper objectMapper,
                                     @Value("${video.stitch.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.objectMapper = objectMapper;
        this.ffmpegPath = StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ObjectNode buildDryRunStitchManifest(ObjectNode manifest) {
        ObjectNode stitch = objectMapper.createObjectNode();
        stitch.put("businessDomain", "pet");
        stitch.put("templateType", "PET_STORY_LONG_VIDEO");
        stitch.put("method", "ffmpeg_concat_segment_order");
        stitch.put("dryRun", true);
        stitch.put("readyForRealStitch", allSegmentsSucceeded(manifest));
        stitch.put("outputSingleVideo", true);
        stitch.put("targetDurationSeconds", intValue(manifest, "totalDurationSeconds", 0));
        stitch.put("aspectRatio", text(manifest, "aspectRatio", "9:16"));

        ArrayNode inputs = objectMapper.createArrayNode();
        ArrayNode subtitleTimeline = objectMapper.createArrayNode();
        for (JsonNode item : manifest.withArray("segments")) {
            ObjectNode segment = (ObjectNode) item;
            int globalStart = intValue(segment, "globalStart", 0);
            ObjectNode input = objectMapper.createObjectNode();
            input.put("segmentIndex", intValue(segment, "segmentIndex", inputs.size() + 1));
            input.put("stitchOrder", intValue(segment, "stitchOrder", inputs.size() + 1));
            input.put("globalStart", globalStart);
            input.put("globalEnd", intValue(segment, "globalEnd", 0));
            input.put("durationSeconds", intValue(segment, "durationSeconds", 0));
            input.put("generationTaskId", longValue(segment, "generationTaskId", 0L));
            input.put("taskStatus", text(segment, "taskStatus", "pending"));
            input.put("resultUrl", text(segment, "resultUrl"));
            input.set("referenceAssetIds", segment.path("referenceAssetIds").deepCopy());
            inputs.add(input);

            for (JsonNode subtitle : segment.withArray("localSubtitles")) {
                ObjectNode global = objectMapper.createObjectNode();
                global.put("segmentIndex", intValue(segment, "segmentIndex", inputs.size()));
                global.put("speakerId", text(subtitle, "speakerId"));
                global.put("text", text(subtitle, "text"));
                global.put("localStart", intValue(subtitle, "start", 0));
                global.put("localEnd", intValue(subtitle, "end", 0));
                global.put("globalStart", globalStart + intValue(subtitle, "start", 0));
                global.put("globalEnd", globalStart + intValue(subtitle, "end", 0));
                subtitleTimeline.add(global);
            }
        }
        stitch.set("segmentInputs", inputs);
        stitch.set("subtitleTimeline", subtitleTimeline);
        stitch.set("voiceMapping", manifest.path("segments").isArray() && manifest.path("segments").size() > 0
                ? manifest.path("segments").get(0).path("voiceMapping").deepCopy()
                : objectMapper.createObjectNode());
        stitch.put("subtitleSource", "segment_local_subtitles_mapped_to_global_timeline");
        stitch.put("audioPolicy", "preserve_segment_audio_then_concat");
        return stitch;
    }

    public StitchResult stitch(ObjectNode manifest) {
        if (!allSegmentsSucceeded(manifest)) {
            throw new BusinessException(40900, "PET_LONG_VIDEO_STITCH_NOT_READY: all segments must be succeeded");
        }
        List<String> urls = resultUrls(manifest);
        if (urls.isEmpty()) {
            throw new BusinessException(40900, "PET_LONG_VIDEO_STITCH_NOT_READY: segment resultUrl is empty");
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("pet-long-video-stitch-");
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                Path target = tempDir.resolve("segment-" + (i + 1) + ".mp4");
                download(urls.get(i), target);
                files.add(target);
            }
            Path output = tempDir.resolve("pet-long-video-final-" + safeName(text(manifest, "compositionId", "composition")) + ".mp4");
            runConcat(files, output, tempDir);
            if (!Files.exists(output) || Files.size(output) <= 0) {
                throw new BusinessException(50100, "PET_LONG_VIDEO_STITCH_FAILED: empty output");
            }
            return new StitchResult(tempDir, output, intValue(manifest, "totalDurationSeconds", 0));
        } catch (BusinessException ex) {
            deleteQuietly(tempDir);
            throw ex;
        } catch (Exception ex) {
            deleteQuietly(tempDir);
            throw new BusinessException(50100, "PET_LONG_VIDEO_STITCH_FAILED: " + ex.getMessage());
        }
    }

    public void cleanup(StitchResult result) {
        if (result != null) {
            deleteQuietly(result.tempDir());
        }
    }

    public boolean allSegmentsSucceeded(ObjectNode manifest) {
        if (manifest == null || !manifest.has("segments") || !manifest.get("segments").isArray()
                || manifest.get("segments").isEmpty()) {
            return false;
        }
        for (JsonNode segment : manifest.withArray("segments")) {
            if (!"succeeded".equals(text(segment, "taskStatus"))) {
                return false;
            }
            if (!StringUtils.hasText(text(segment, "resultUrl"))) {
                return false;
            }
        }
        return true;
    }

    private List<String> resultUrls(ObjectNode manifest) {
        List<String> urls = new ArrayList<>();
        for (JsonNode segment : manifest.withArray("segments")) {
            String url = text(segment, "resultUrl");
            if (StringUtils.hasText(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private void download(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException(50200, "PET_LONG_VIDEO_SEGMENT_DOWNLOAD_FAILED: HTTP " + response.statusCode());
        }
    }

    private void runConcat(List<Path> segmentFiles, Path output, Path tempDir) throws Exception {
        Path concat = tempDir.resolve("concat.txt");
        StringBuilder list = new StringBuilder();
        for (Path file : segmentFiles) {
            list.append("file '").append(file.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''")).append("'\n");
        }
        Files.writeString(concat, list.toString(), StandardCharsets.UTF_8);
        Path log = tempDir.resolve("ffmpeg-concat.log");
        Process process = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concat.toString(),
                "-c", "copy",
                "-movflags", "+faststart",
                output.toString()
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(50100, "PET_LONG_VIDEO_STITCH_TIMEOUT");
        }
        if (process.exitValue() == 0) {
            return;
        }
        Path reencodeLog = tempDir.resolve("ffmpeg-concat-reencode.log");
        Process fallback = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concat.toString(),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-c:a", "aac",
                "-movflags", "+faststart",
                output.toString()
        ).redirectErrorStream(true).redirectOutput(reencodeLog.toFile()).start();
        boolean fallbackFinished = fallback.waitFor(10, TimeUnit.MINUTES);
        if (!fallbackFinished) {
            fallback.destroyForcibly();
            throw new BusinessException(50100, "PET_LONG_VIDEO_STITCH_REENCODE_TIMEOUT");
        }
        if (fallback.exitValue() != 0) {
            String message = Files.exists(reencodeLog) ? Files.readString(reencodeLog, StandardCharsets.UTF_8) : "";
            throw new BusinessException(50100, "PET_LONG_VIDEO_STITCH_FAILED: " + limit(message, 500));
        }
    }

    private void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : fallback;
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToLong() ? node.get(field).asLong() : fallback;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String safeName(String value) {
        String safe = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return StringUtils.hasText(safe) ? safe : "unknown";
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    public record StitchResult(Path tempDir, Path outputFile, int durationSeconds) {
    }
}
