package com.huashuo.avatar.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.avatar.config.VolcengineImageProperties;
import com.huashuo.common.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doubao Seedream 图片生成客户端，对应项目根目录 api.md 的 /images/generations。
 */
@Component
public class DoubaoImageClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final VolcengineImageProperties properties;
    private final ObjectMapper objectMapper;

    public DoubaoImageClient(VolcengineImageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<String> generateImages(String prompt, List<String> referenceImageUrls, int imageCount, String size)
            throws IOException, InterruptedException {
        if (!properties.configured()) {
            throw new BusinessException(50100, "Volcengine image generation is not configured");
        }
        int safeCount = Math.max(1, Math.min(imageCount, 4));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.effectiveModel());
        body.put("prompt", prompt);
        body.put("response_format", "url");
        body.put("size", size == null || size.isBlank() ? properties.effectiveDefaultSize() : size);
        body.put("stream", false);
        body.put("watermark", properties.effectiveWatermark());
        body.put("sequential_image_generation", safeCount > 1 ? "auto" : "disabled");
        if (safeCount > 1) {
            body.put("sequential_image_generation_options", Map.of("max_images", safeCount));
        }
        List<String> validReferenceUrls = normalizeReferenceUrls(referenceImageUrls);
        if (!validReferenceUrls.isEmpty()) {
            body.put("image", validReferenceUrls.size() == 1 ? validReferenceUrls.get(0) : validReferenceUrls);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.effectiveBaseUrl() + "/images/generations"))
                .timeout(Duration.ofMinutes(3))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new BusinessException(50100, "Doubao image generation failed: " + response.body());
        }
        List<String> urls = extractUrls(objectMapper.readTree(response.body()));
        if (urls.isEmpty()) {
            throw new BusinessException(50100, "Doubao image generation returned no image URL");
        }
        return urls.size() > safeCount ? urls.subList(0, safeCount) : urls;
    }

    public DownloadedImage downloadImage(String imageUrl, Path targetFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();
        HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download image failed, HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("image/png");
        return new DownloadedImage(contentType);
    }

    private List<String> extractUrls(JsonNode root) {
        List<String> urls = new ArrayList<>();
        collectUrls(root.path("data"), urls);
        collectUrls(root.path("images"), urls);
        collectUrls(root.path("result"), urls);
        return urls.stream().distinct().toList();
    }

    private void collectUrls(JsonNode node, List<String> urls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectUrls(child, urls));
            return;
        }
        if (node.isObject()) {
            addTextUrl(node.path("url"), urls);
            addTextUrl(node.path("image_url"), urls);
            addTextUrl(node.path("imageUrl"), urls);
            node.fields().forEachRemaining(entry -> collectUrls(entry.getValue(), urls));
        }
    }

    private void addTextUrl(JsonNode node, List<String> urls) {
        if (node != null && node.isTextual() && !node.asText().isBlank() && node.asText().startsWith("http")) {
            urls.add(node.asText());
        }
    }

    private List<String> normalizeReferenceUrls(List<String> referenceImageUrls) {
        if (referenceImageUrls == null || referenceImageUrls.isEmpty()) {
            return List.of();
        }
        List<String> urls = referenceImageUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .filter(url -> url.startsWith("http://") || url.startsWith("https://"))
                .toList();
        if (urls.size() != referenceImageUrls.size()) {
            throw new BusinessException(40000, "Reference image must be an absolute http/https URL");
        }
        return urls;
    }

    public record DownloadedImage(String mimeType) {
    }
}
