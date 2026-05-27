package com.huashuo.video.subtitle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.video.config.VolcengineSubtitleProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class VolcengineSubtitleClient {

    private static final int CODE_SUCCESS = 0;
    private static final int CODE_PROCESSING = 2000;

    private final VolcengineSubtitleProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VolcengineSubtitleClient(VolcengineSubtitleProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public SubtitleResult createSrtFromAudioUrl(String audioUrl) {
        ensureConfigured();
        String jobId = submit(audioUrl);
        JsonNode result = poll(jobId);
        String srt = toSrt(utterancesOf(result));
        if (!StringUtils.hasText(srt)) {
            throw new BusinessException(50213, "Volcengine subtitle result is empty");
        }
        return new SubtitleResult(jobId, srt, result);
    }

    private String submit(String audioUrl) {
        if (!StringUtils.hasText(audioUrl)) {
            throw new BusinessException(40000, "subtitle audio url is required");
        }
        try {
            String url = properties.effectiveSubmitUrl() + "?" + submitQuery();
            String body = objectMapper.writeValueAsString(Map.of("url", audioUrl.trim()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = parseResponse(response, "submit");
            int code = json.path("code").asInt(-1);
            if (code != CODE_SUCCESS) {
                throw new BusinessException(50212, "Volcengine subtitle submit failed: " + messageOf(json));
            }
            String id = firstText(json.path("id"), json.path("result").path("id"), json.path("data").path("id"));
            if (!StringUtils.hasText(id)) {
                throw new BusinessException(50212, "Volcengine subtitle submit returned empty task id");
            }
            return id;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(50212, "Volcengine subtitle submit failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50212, "Volcengine subtitle submit was interrupted");
        } catch (Exception e) {
            throw new BusinessException(50212, "Volcengine subtitle submit failed: " + e.getMessage());
        }
    }

    private JsonNode poll(String jobId) {
        long deadline = System.currentTimeMillis() + properties.effectivePollTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode json = query(jobId);
            int code = json.path("code").asInt(-1);
            if (code == CODE_SUCCESS) {
                return json;
            }
            if (code != CODE_PROCESSING) {
                throw new BusinessException(50213, "Volcengine subtitle query failed: " + messageOf(json));
            }
            sleep();
        }
        throw new BusinessException(50213, "Volcengine subtitle query timed out");
    }

    private JsonNode query(String jobId) {
        try {
            String url = properties.effectiveQueryUrl()
                    + "?appid=" + enc(properties.appId().trim())
                    + "&id=" + enc(jobId)
                    + "&blocking=0";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "*/*")
                    .header("Authorization", authHeader())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response, "query");
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(50213, "Volcengine subtitle query failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50213, "Volcengine subtitle query was interrupted");
        } catch (Exception e) {
            throw new BusinessException(50213, "Volcengine subtitle query failed: " + e.getMessage());
        }
    }

    private JsonNode parseResponse(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException(action.equals("submit") ? 50212 : 50213,
                    "Volcengine subtitle " + action + " HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode utterancesOf(JsonNode result) {
        JsonNode utterances = result.path("utterances");
        if (utterances.isArray()) {
            return utterances;
        }
        utterances = result.path("result").path("utterances");
        if (utterances.isArray()) {
            return utterances;
        }
        return result.path("data").path("utterances");
    }

    private String submitQuery() {
        List<String> parts = new ArrayList<>();
        parts.add("appid=" + enc(properties.appId().trim()));
        parts.add("language=" + enc(properties.effectiveLanguage()));
        parts.add("use_itn=" + bool(properties.useItn()));
        parts.add("use_capitalize=" + bool(properties.useCapitalize()));
        parts.add("use_punc=" + bool(properties.usePunc()));
        parts.add("caption_type=" + enc(properties.effectiveCaptionType()));
        parts.add("max_lines=" + properties.effectiveMaxLines());
        parts.add("words_per_line=" + properties.effectiveWordsPerLine());
        return String.join("&", parts);
    }

    private String toSrt(JsonNode utterances) {
        if (utterances == null || !utterances.isArray() || utterances.isEmpty()) {
            return "";
        }
        List<JsonNode> rows = new ArrayList<>();
        utterances.forEach(rows::add);
        rows.sort(Comparator.comparingLong(node -> node.path("start_time").asLong(0L)));
        StringBuilder srt = new StringBuilder();
        int index = 1;
        for (JsonNode row : rows) {
            String text = row.path("text").asText("").replaceAll("[\\r\\n]+", " ").trim();
            long start = row.path("start_time").asLong(-1L);
            long end = row.path("end_time").asLong(-1L);
            if (!StringUtils.hasText(text) || start < 0 || end <= start) {
                continue;
            }
            srt.append(index++).append('\n')
                    .append(formatSrtTime(start))
                    .append(" --> ")
                    .append(formatSrtTime(end))
                    .append('\n')
                    .append(text)
                    .append("\n\n");
        }
        return srt.toString();
    }

    private String formatSrtTime(long ms) {
        long value = Math.max(0L, ms);
        long hours = value / 3_600_000L;
        value %= 3_600_000L;
        long minutes = value / 60_000L;
        value %= 60_000L;
        long seconds = value / 1000L;
        long millis = value % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private void sleep() {
        try {
            Thread.sleep(properties.effectivePollIntervalSeconds() * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50213, "Volcengine subtitle query was interrupted");
        }
    }

    private void ensureConfigured() {
        if (!properties.enabled()) {
            throw new BusinessException(50001, "Volcengine subtitle is disabled");
        }
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.accessToken())) {
            throw new BusinessException(50001, "Volcengine subtitle credentials missing; set VOLCENGINE_SUBTITLE_APP_ID and VOLCENGINE_SUBTITLE_ACCESS_TOKEN");
        }
    }

    private String authHeader() {
        return "Bearer; " + properties.accessToken().trim();
    }

    private String messageOf(JsonNode json) {
        String message = firstText(json.path("message"), json.path("msg"), json.path("error"));
        if (StringUtils.hasText(message)) {
            return message;
        }
        return json.toString();
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                String value = node.asText(null);
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String bool(boolean value) {
        return value ? "True" : "False";
    }

    public record SubtitleResult(String jobId, String srt, JsonNode rawResult) {
    }
}
