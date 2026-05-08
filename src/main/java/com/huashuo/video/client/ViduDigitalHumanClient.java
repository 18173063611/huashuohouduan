package com.huashuo.video.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.video.config.ViduDigitalHumanProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class ViduDigitalHumanClient {

    private final ViduDigitalHumanProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ViduDigitalHumanClient(ViduDigitalHumanProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public CreateTaskResponse create(CreateTaskRequest payload) {
        ensureConfigured();
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = baseRequest("/ent/v2/digital-human")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccess(response.statusCode(), response.body());
            return objectMapper.readValue(response.body(), CreateTaskResponse.class);
        } catch (IOException e) {
            throw new BusinessException(50100, "Vidu digital human create request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50000, "Vidu digital human create request interrupted");
        }
    }

    public CreationQueryResponse queryCreations(String taskId) {
        ensureConfigured();
        if (!StringUtils.hasText(taskId)) {
            throw new BusinessException(40000, "Vidu task id is required");
        }
        try {
            HttpRequest request = baseRequest("/ent/v2/tasks/" + taskId.trim() + "/creations")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccess(response.statusCode(), response.body());
            return objectMapper.readValue(response.body(), CreationQueryResponse.class);
        } catch (IOException e) {
            throw new BusinessException(50100, "Vidu digital human query request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50000, "Vidu digital human query request interrupted");
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.effectiveBaseUrl() + path))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Authorization", "Token " + properties.apiKey().trim());
    }

    private void ensureConfigured() {
        if (!properties.configured()) {
            throw new BusinessException(50001, "Vidu digital human is not configured; set VIDU_API_KEY.");
        }
    }

    private void assertSuccess(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new BusinessException(50100, "Vidu API returned HTTP " + statusCode + ": " + body);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateTaskRequest(
            String model,
            String image,
            String prompt,
            @JsonProperty("audio_url") String audioUrl,
            String text,
            @JsonProperty("voice_id") String voiceId,
            String resolution,
            String payload,
            @JsonProperty("callback_url") String callbackUrl
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateTaskResponse(
            @JsonProperty("task_id") String taskId,
            String state,
            String image,
            String prompt,
            @JsonProperty("audio_url") String audioUrl,
            String text,
            @JsonProperty("voice_id") String voiceId,
            String resolution,
            String payload,
            Integer credits,
            @JsonProperty("created_at") String createdAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreationQueryResponse(
            String id,
            String state,
            @JsonProperty("err_code") String errCode,
            Integer credits,
            String payload,
            List<CreationItem> creations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreationItem(
            String id,
            String url,
            @JsonProperty("cover_url") String coverUrl,
            @JsonProperty("watermarked_url") String watermarkedUrl
    ) {
    }
}
