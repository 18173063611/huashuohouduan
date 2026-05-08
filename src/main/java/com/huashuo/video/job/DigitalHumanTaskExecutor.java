package com.huashuo.video.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.client.ViduDigitalHumanClient;
import com.huashuo.video.client.ViduDigitalHumanClient.CreateTaskRequest;
import com.huashuo.video.client.ViduDigitalHumanClient.CreateTaskResponse;
import com.huashuo.video.client.ViduDigitalHumanClient.CreationItem;
import com.huashuo.video.client.ViduDigitalHumanClient.CreationQueryResponse;
import com.huashuo.video.config.ViduDigitalHumanProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DigitalHumanTaskExecutor {

    private static final String STATE_SUCCESS = "success";
    private static final String STATE_FAILED = "failed";

    private final TaskService taskService;
    private final ViduDigitalHumanClient client;
    private final ViduDigitalHumanProperties properties;
    private final StorageService storageService;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DigitalHumanTaskExecutor(
            TaskService taskService,
            ViduDigitalHumanClient client,
            ViduDigitalHumanProperties properties,
            StorageService storageService,
            AssetService assetService,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.client = client;
        this.properties = properties;
        this.storageService = storageService;
        this.assetService = assetService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Async("voiceTtsAsyncExecutor")
    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            return;
        }

        try {
            TaskItem task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            String imageUrl = requiredText(input, "imageUrl");
            String audioUrl = trimToNull(input.path("audioUrl").asText(null));
            String text = trimToNull(input.path("text").asText(null));
            String voiceId = trimToNull(input.path("voiceId").asText(null));
            String prompt = trimToNull(input.path("prompt").asText(null));
            String resolution = StringUtils.hasText(input.path("resolution").asText(null))
                    ? input.path("resolution").asText().trim()
                    : properties.effectiveResolution();
            String model = StringUtils.hasText(input.path("model").asText(null))
                    ? input.path("model").asText().trim()
                    : properties.effectiveModel();

            if (!StringUtils.hasText(audioUrl) && !StringUtils.hasText(text)) {
                throw new BusinessException(40000, "audioUrl or text is required");
            }

            taskService.updateTaskProgress(taskId, 20);
            CreateTaskResponse created = client.create(new CreateTaskRequest(
                    model,
                    imageUrl,
                    prompt,
                    audioUrl,
                    audioUrl == null ? text : null,
                    audioUrl == null ? voiceId : null,
                    resolution,
                    task.traceId(),
                    null
            ));
            if (created == null || !StringUtils.hasText(created.taskId())) {
                throw new BusinessException(50100, "Vidu did not return task_id");
            }

            taskService.updateTaskProgress(taskId, 30);
            CreationQueryResponse finalState = poll(taskId, created.taskId());
            CreationItem creation = firstCreation(finalState);
            String videoUrl = StringUtils.hasText(creation.url()) ? creation.url() : creation.watermarkedUrl();
            if (!StringUtils.hasText(videoUrl)) {
                throw new BusinessException(50300, "Vidu task succeeded but video url is empty");
            }

            taskService.updateTaskProgress(taskId, 94);
            UploadResult stored = downloadAndStore(videoUrl, created.taskId());
            AssetItem asset = assetService.createGeneratedVideoAsset(
                    task.ownerUserId(),
                    task.projectId(),
                    task.taskId(),
                    stored.filename(),
                    stored.objectKey(),
                    stored.url(),
                    trimToNull(creation.coverUrl()),
                    stored.contentType(),
                    stored.size(),
                    "VIDU_DIGITAL_HUMAN",
                    metadata(created, finalState, creation, input)
            );

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("provider", "VIDU");
            output.put("viduTaskId", created.taskId());
            output.put("model", model);
            output.put("status", "succeeded");
            output.put("videoUrl", stored.url());
            output.put("resultAssetId", asset.assetId());
            output.put("coverUrl", creation.coverUrl());
            output.put("credits", finalState.credits());
            output.put("viduState", finalState.state());
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (Exception e) {
            try {
                taskService.failTask(taskId, e.getMessage() == null ? "Vidu digital human task failed" : e.getMessage(), true);
            } catch (Exception ignored) {
            }
        }
    }

    private CreationQueryResponse poll(Long localTaskId, String viduTaskId) {
        long deadline = System.currentTimeMillis() + properties.effectivePollTimeoutSeconds() * 1000L;
        long interval = properties.effectivePollIntervalSeconds() * 1000L;
        int progress = 35;
        while (true) {
            sleep(interval);
            CreationQueryResponse response = client.queryCreations(viduTaskId);
            String state = response == null ? "" : response.state();
            if (STATE_SUCCESS.equalsIgnoreCase(state)) {
                taskService.updateTaskProgress(localTaskId, 90);
                return response;
            }
            if (STATE_FAILED.equalsIgnoreCase(state)) {
                throw new BusinessException(50300, "Vidu digital human task failed: " + response.errCode());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new BusinessException(50300, "Vidu digital human task polling timed out, taskId=" + viduTaskId + ", state=" + state);
            }
            progress = Math.min(88, progress + 4);
            taskService.updateTaskProgress(localTaskId, progress);
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
                            JsonNode input) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "VIDU");
        meta.put("taskId", created.taskId());
        meta.put("creationId", creation.id());
        meta.put("state", finalState.state());
        meta.put("credits", finalState.credits());
        meta.put("imageUrl", input.path("imageUrl").asText(null));
        meta.put("audioUrl", input.path("audioUrl").asText(null));
        meta.put("text", input.path("text").asText(null));
        meta.put("resolution", created.resolution());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{\"provider\":\"VIDU\"}";
        }
    }

    private String requiredText(JsonNode input, String field) {
        String value = trimToNull(input.path(field).asText(null));
        if (value == null) {
            throw new BusinessException(40000, field + " is required");
        }
        return value;
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
