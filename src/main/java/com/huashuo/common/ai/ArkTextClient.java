package com.huashuo.common.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ArkTextClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public ArkTextClient(
            ObjectMapper objectMapper,
            @Value("${volcengine.ark.base-url:https://ark.cn-beijing.volces.com/api/v3}") String baseUrl,
            @Value("${volcengine.ark.api-key:}") String apiKey,
            @Value("${volcengine.ark.model:doubao-seed-2-0-mini-260215}") String defaultModel
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    public boolean available() {
        return StringUtils.hasText(apiKey) && StringUtils.hasText(baseUrl);
    }

    public ArkChatResult chat(String prompt, Duration timeout) {
        return chat(prompt, defaultModel, timeout);
    }

    public ArkChatResult chat(String prompt, String model, Duration timeout) {
        if (!available()) {
            throw new BusinessException(50214, "Volcengine Ark is not configured");
        }
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(50214, "Volcengine Ark model is not configured");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40000, "Prompt is required");
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model.trim(),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt
                    ))
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout == null ? Duration.ofSeconds(90) : timeout)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50214, "Volcengine Ark request failed: " + errorMessage(response));
            }
            return parseResponse(response.body(), model.trim());
        } catch (IOException exception) {
            throw new BusinessException(50214, "Volcengine Ark request failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50214, "Volcengine Ark request was interrupted");
        }
    }

    private ArkChatResult parseResponse(String body, String fallbackModel) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String content = firstNonBlank(
                    textByPointer(root, "/choices/0/message/content"),
                    textByPointer(root, "/choices/0/text")
            );
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(50214, "Volcengine Ark returned empty text");
            }
            JsonNode usage = root.at("/usage");
            return new ArkChatResult(
                    content.trim(),
                    firstNonBlank(textByPointer(root, "/model"), fallbackModel),
                    intByPointer(root, "/usage/prompt_tokens"),
                    intByPointer(root, "/usage/completion_tokens"),
                    intByPointer(root, "/usage/total_tokens"),
                    usage.isMissingNode() ? null : objectMapper.writeValueAsString(usage)
            );
        } catch (IOException exception) {
            throw new BusinessException(50214, "Volcengine Ark returned invalid JSON: " + exception.getMessage());
        }
    }

    private String errorMessage(HttpResponse<String> response) {
        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return "http=" + response.statusCode() + ", empty response body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return "http=" + response.statusCode() + ", message=" + firstNonBlank(
                    textByPointer(root, "/error/message"),
                    textByPointer(root, "/message"),
                    abbreviate(body, 500)
            );
        } catch (IOException exception) {
            return "http=" + response.statusCode() + ", body=" + abbreviate(body, 500);
        }
    }

    private String textByPointer(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }

    private Integer intByPointer(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        return value.isNumber() ? value.asInt() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
