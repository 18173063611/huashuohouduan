package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.video.DTO.DigitalHumanDTO;
import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.client.ViduDigitalHumanClient;
import com.huashuo.video.client.ViduDigitalHumanClient.CreateTaskRequest;
import com.huashuo.video.client.ViduDigitalHumanClient.CreateTaskResponse;
import com.huashuo.video.client.ViduDigitalHumanClient.CreationItem;
import com.huashuo.video.client.ViduDigitalHumanClient.CreationQueryResponse;
import com.huashuo.video.config.ViduDigitalHumanProperties;
import com.huashuo.video.service.ViduDigitalHumanService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ViduDigitalHumanServiceImpl implements ViduDigitalHumanService {

    private static final String STATE_SUCCESS = "success";
    private static final String STATE_FAILED = "failed";

    private final ViduDigitalHumanClient client;
    private final ViduDigitalHumanProperties properties;
    private final StorageService storageService;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ViduDigitalHumanServiceImpl(
            ViduDigitalHumanClient client,
            ViduDigitalHumanProperties properties,
            StorageService storageService,
            AssetService assetService,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.properties = properties;
        this.storageService = storageService;
        this.assetService = assetService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public VideoTaskVO generate(DigitalHumanDTO request, String traceId) {
        if (request == null || !StringUtils.hasText(request.getImageUrl())) {
            throw new BusinessException(40000, "imageUrl is required");
        }
        if (!StringUtils.hasText(request.getAudioUrl()) && !StringUtils.hasText(request.getText())) {
            throw new BusinessException(40000, "audioUrl or text is required");
        }

        String resolution = StringUtils.hasText(request.getResolution())
                ? request.getResolution().trim()
                : properties.effectiveResolution();
        String model = StringUtils.hasText(request.getModel())
                ? request.getModel().trim()
                : properties.effectiveModel();

        CreateTaskResponse created = client.create(new CreateTaskRequest(
                model,
                request.getImageUrl().trim(),
                trimToNull(request.getPrompt()),
                trimToNull(request.getAudioUrl()),
                trimToNull(request.getAudioUrl()) == null ? trimToNull(request.getText()) : null,
                trimToNull(request.getAudioUrl()) == null ? trimToNull(request.getVoiceId()) : null,
                resolution,
                traceId,
                null
        ));
        if (created == null || !StringUtils.hasText(created.taskId())) {
            throw new BusinessException(50100, "Vidu did not return task_id");
        }

        CreationQueryResponse finalState = poll(created.taskId());
        CreationItem creation = firstCreation(finalState);
        String videoUrl = StringUtils.hasText(creation.url()) ? creation.url() : creation.watermarkedUrl();
        if (!StringUtils.hasText(videoUrl)) {
            throw new BusinessException(50300, "Vidu task succeeded but video url is empty");
        }

        UploadResult stored = downloadAndStore(videoUrl, created.taskId());
        AssetItem asset = assetService.createGeneratedVideoAsset(
                request.getOwnerUserId(),
                request.getProjectId(),
                null,
                stored.filename(),
                stored.objectKey(),
                stored.url(),
                trimToNull(creation.coverUrl()),
                stored.contentType(),
                stored.size(),
                "VIDU_DIGITAL_HUMAN",
                metadata(created, finalState, creation, request)
        );

        return VideoTaskVO.builder()
                .taskId(created.taskId())
                .model(model)
                .status("succeeded")
                .videoUrl(stored.url())
                .resultAssetId(asset.assetId())
                .lastFrameUrl(creation.coverUrl())
                .completionTokens(finalState.credits())
                .build();
    }

    private CreationQueryResponse poll(String taskId) {
        long deadline = System.currentTimeMillis() + properties.effectivePollTimeoutSeconds() * 1000L;
        long interval = properties.effectivePollIntervalSeconds() * 1000L;
        while (true) {
            sleep(interval);
            CreationQueryResponse response = client.queryCreations(taskId);
            String state = response == null ? "" : response.state();
            if (STATE_SUCCESS.equalsIgnoreCase(state)) {
                return response;
            }
            if (STATE_FAILED.equalsIgnoreCase(state)) {
                throw new BusinessException(50300, "Vidu digital human task failed: " + response.errCode());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new BusinessException(50300, "Vidu digital human task polling timed out, taskId=" + taskId + ", state=" + state);
            }
        }
    }

    private CreationItem firstCreation(CreationQueryResponse response) {
        if (response == null || response.creations() == null || response.creations().isEmpty()) {
            throw new BusinessException(50300, "Vidu task has no creations");
        }
        return response.creations().get(0);
    }

    private UploadResult downloadAndStore(String videoUrl, String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(videoUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50100, "Failed to download Vidu video, HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("video/mp4");
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            String fileName = "vidu-digital-human-" + taskId + ".mp4";
            try (InputStream in = response.body()) {
                return storageService.upload(in, contentLength, fileName, contentType, "video");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "Failed to store Vidu video: " + e.getMessage());
        }
    }

    private String metadata(CreateTaskResponse created, CreationQueryResponse finalState, CreationItem creation,
                            DigitalHumanDTO request) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "VIDU");
        meta.put("taskId", created.taskId());
        meta.put("creationId", creation.id());
        meta.put("state", finalState.state());
        meta.put("credits", finalState.credits());
        meta.put("imageUrl", request.getImageUrl());
        meta.put("audioUrl", request.getAudioUrl());
        meta.put("text", request.getText());
        meta.put("resolution", created.resolution());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{\"provider\":\"VIDU\"}";
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50000, "Vidu digital human polling interrupted");
        }
    }
}
