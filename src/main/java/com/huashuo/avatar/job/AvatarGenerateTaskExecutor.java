package com.huashuo.avatar.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.avatar.client.DoubaoImageClient;
import com.huashuo.avatar.config.VolcengineImageProperties;
import com.huashuo.avatar.entity.AvatarProfileEntity;
import com.huashuo.avatar.mapper.AvatarProfileMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.service.TaskService;
import com.huashuo.upload.config.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步执行数字人形象生成：RUNNING -> Seedream -> 图片落盘 -> 资产/形象记录 -> SUCCESS。
 */
@Component
public class AvatarGenerateTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AvatarGenerateTaskExecutor.class);

    private final TaskService taskService;
    private final AssetService assetService;
    private final AvatarProfileMapper avatarProfileMapper;
    private final DoubaoImageClient doubaoImageClient;
    private final VolcengineImageProperties imageProperties;
    private final UploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    public AvatarGenerateTaskExecutor(
            TaskService taskService,
            AssetService assetService,
            AvatarProfileMapper avatarProfileMapper,
            DoubaoImageClient doubaoImageClient,
            VolcengineImageProperties imageProperties,
            UploadProperties uploadProperties,
            ObjectMapper objectMapper
    ) {
        this.taskService = taskService;
        this.assetService = assetService;
        this.avatarProfileMapper = avatarProfileMapper;
        this.doubaoImageClient = doubaoImageClient;
        this.imageProperties = imageProperties;
        this.uploadProperties = uploadProperties;
        this.objectMapper = objectMapper;
    }

    @Async("voiceTtsAsyncExecutor")
    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("Avatar task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        try {
            var task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            long projectId = input.path("projectId").asLong();
            String avatarName = input.path("avatarName").asText("数字人形象");
            String prompt = input.path("prompt").asText("");
            String style = input.path("style").asText("REALISTIC");
            String size = input.path("size").asText(imageProperties.effectiveDefaultSize());
            int imageCount = input.path("imageCount").asInt(4);
            List<String> referenceImageUrls = new ArrayList<>();
            input.path("referenceImageUrls").forEach(node -> referenceImageUrls.add(node.asText()));

            if (!imageProperties.configured()) {
                taskService.failTask(taskId, "50100: Volcengine image credentials missing; set volcengine.image.api-key", true);
                return;
            }

            List<String> remoteUrls = doubaoImageClient.generateImages(prompt, referenceImageUrls, imageCount, size);
            String datePath = LocalDate.now().toString();
            Path dir = Path.of(uploadProperties.localRoot(), "avatar", datePath);
            Files.createDirectories(dir);

            List<Long> assetIds = new ArrayList<>();
            List<Long> avatarIds = new ArrayList<>();
            List<String> previewUrls = new ArrayList<>();

            for (int i = 0; i < remoteUrls.size(); i++) {
                String remoteUrl = remoteUrls.get(i);
                String fileName = "avatar-" + taskId + "-" + (i + 1) + ".png";
                Path target = dir.resolve(fileName);
                DoubaoImageClient.DownloadedImage downloaded = doubaoImageClient.downloadImage(remoteUrl, target);
                long fileSize = Files.size(target);
                String previewUrl = uploadProperties.previewPrefix() + "/avatar/" + datePath + "/" + fileName;
                String metadataJson = buildMeta(input, remoteUrl, style, i + 1);
                AssetItem asset = assetService.createAvatarImageAsset(
                        projectId,
                        taskId,
                        fileName,
                        target.toAbsolutePath().toString(),
                        previewUrl,
                        downloaded.mimeType(),
                        fileSize,
                        "AI_GENERATED",
                        metadataJson
                );

                AvatarProfileEntity avatar = new AvatarProfileEntity();
                avatar.setProjectId(projectId);
                avatar.setTaskId(taskId);
                avatar.setAssetId(asset.assetId());
                avatar.setAvatarName(remoteUrls.size() == 1 ? avatarName : avatarName + " " + (i + 1));
                avatar.setSourceType("AI_GENERATED");
                avatar.setPrompt(prompt);
                avatar.setReferenceAssetIds(objectMapper.writeValueAsString(input.path("referenceAssetIds")));
                avatar.setPreviewUrl(asset.fileUrl());
                avatar.setMetadataJson(metadataJson);
                avatar.setDefaultAvatar(0);
                avatarProfileMapper.insert(avatar);

                assetIds.add(asset.assetId());
                avatarIds.add(avatar.getAvatarId());
                previewUrls.add(asset.fileUrl());
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("assetIds", assetIds);
            output.put("avatarIds", avatarIds);
            output.put("previewUrls", previewUrls);
            output.put("remoteImageUrls", remoteUrls);
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("Avatar task {} failed: {}", taskId, ex.getMessage());
            taskService.failTask(taskId, ex.getMessage(), true);
        } catch (Exception ex) {
            log.error("Avatar task {} error", taskId, ex);
            taskService.failTask(taskId, ex.getMessage() == null ? "Avatar generation unknown error" : ex.getMessage(), true);
        }
    }

    private String buildMeta(JsonNode input, String remoteUrl, String style, int index) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", imageProperties.effectiveModel());
        meta.put("size", input.path("size").asText(imageProperties.effectiveDefaultSize()));
        meta.put("style", style);
        meta.put("imageIndex", index);
        meta.put("prompt", input.path("prompt").asText(""));
        meta.put("remoteImageUrl", remoteUrl);
        meta.put("source", "DOUBAO_SEEDREAM");
        return objectMapper.writeValueAsString(meta);
    }
}
