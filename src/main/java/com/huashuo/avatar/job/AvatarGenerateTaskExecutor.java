package com.huashuo.avatar.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.avatar.client.DoubaoImageClient;
import com.huashuo.avatar.config.VolcengineImageProperties;
import com.huashuo.avatar.entity.AvatarProfileEntity;
import com.huashuo.avatar.mapper.AvatarProfileMapper;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步执行数字人形象生成：RUNNING -> Seedream -> 远程图流式写入 TOS -> 资产/形象记录 -> SUCCESS。
 */
@Component
public class AvatarGenerateTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AvatarGenerateTaskExecutor.class);
    private static final String AVATAR_ASSET_GROUP = "数字人素材";

    private final TaskService taskService;
    private final AssetService assetService;
    private final AvatarProfileMapper avatarProfileMapper;
    private final DoubaoImageClient doubaoImageClient;
    private final VolcengineImageProperties imageProperties;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final CreditBillingService creditBillingService;

    public AvatarGenerateTaskExecutor(
            TaskService taskService,
            AssetService assetService,
            AvatarProfileMapper avatarProfileMapper,
            DoubaoImageClient doubaoImageClient,
            VolcengineImageProperties imageProperties,
            StorageService storageService,
            ObjectMapper objectMapper,
            CreditBillingService creditBillingService
    ) {
        this.taskService = taskService;
        this.assetService = assetService;
        this.avatarProfileMapper = avatarProfileMapper;
        this.doubaoImageClient = doubaoImageClient;
        this.imageProperties = imageProperties;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.creditBillingService = creditBillingService;
    }

    public void run(Long taskId) {
        try {
            taskService.startTask(taskId);
        } catch (Exception e) {
            log.warn("Avatar task {} cannot start: {}", taskId, e.getMessage());
            return;
        }

        final boolean[] refundIfFail = {true};
        try {
            var task = taskService.getTask(taskId);
            JsonNode input = objectMapper.readTree(task.inputJson() == null ? "{}" : task.inputJson());
            Long projectId = input.hasNonNull("projectId") ? input.path("projectId").asLong() : null;
            Long ownerUserId = input.hasNonNull("requestingUserId") ? input.path("requestingUserId").asLong() : null;
            String avatarName = input.path("avatarName").asText("数字人形象");
            String prompt = input.path("prompt").asText("");
            String style = input.path("style").asText("REALISTIC");
            String size = input.path("size").asText(imageProperties.effectiveDefaultSize());
            int imageCount = input.path("imageCount").asInt(4);
            List<String> referenceImageUrls = new ArrayList<>();
            input.path("referenceImageUrls").forEach(node -> referenceImageUrls.add(node.asText()));

            if (!imageProperties.configured()) {
                throw new BusinessException(50100, "Volcengine image credentials missing; set volcengine.image.api-key");
            }

            taskService.updateTaskProgress(taskId, 25);
            List<String> remoteUrls = doubaoImageClient.generateImages(prompt, referenceImageUrls, imageCount, size);
            refundIfFail[0] = false;
            taskService.updateTaskProgress(taskId, 55);

            List<Long> assetIds = new ArrayList<>();
            List<Long> avatarIds = new ArrayList<>();
            List<String> previewUrls = new ArrayList<>();

            int n = remoteUrls.size();
            for (int i = 0; i < n; i++) {
                String remoteUrl = remoteUrls.get(i);
                String fileName = "avatar-" + taskId + "-" + (i + 1) + ".png";
                HttpResponse<InputStream> imgResp = doubaoImageClient.openImageDownload(remoteUrl);
                String contentType = imgResp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("image/png");
                long contentLen = imgResp.headers().firstValue(HttpHeaders.CONTENT_LENGTH)
                        .map(Long::parseLong).orElse(-1L);

                UploadResult stored;
                try (InputStream in = imgResp.body()) {
                    stored = storageService.upload(in, contentLen, fileName, contentType, "avatar");
                }

                String metadataJson = buildMeta(input, remoteUrl, style, i + 1);
                AssetItem asset = assetService.createAvatarImageAsset(
                        ownerUserId,
                        projectId,
                        taskId,
                        fileName,
                        stored.objectKey(),
                        stored.url(),
                        contentType,
                        stored.size(),
                        TaskTypeCode.AVATAR_GENERATE,
                        metadataJson
                );

                AvatarProfileEntity avatar = new AvatarProfileEntity();
                avatar.setProjectId(projectId);
                avatar.setTaskId(taskId);
                avatar.setAssetId(asset.assetId());
                avatar.setAvatarName(n == 1 ? avatarName : avatarName + " " + (i + 1));
                avatar.setSourceType(TaskTypeCode.AVATAR_GENERATE);
                avatar.setPrompt(prompt);
                avatar.setReferenceAssetIds(objectMapper.writeValueAsString(input.path("referenceAssetIds")));
                avatar.setPreviewUrl(asset.fileUrl());
                avatar.setMetadataJson(metadataJson);
                avatar.setDefaultAvatar(0);
                avatarProfileMapper.insert(avatar);

                assetIds.add(asset.assetId());
                avatarIds.add(avatar.getAvatarId());
                previewUrls.add(asset.fileUrl());

                if (n > 0) {
                    int stageProgress = 55 + (int) Math.round((i + 1) * 30.0 / n);
                    taskService.updateTaskProgress(taskId, stageProgress);
                }
            }

            taskService.updateTaskProgress(taskId, 95);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("assetIds", assetIds);
            output.put("avatarIds", avatarIds);
            output.put("previewUrls", previewUrls);
            output.put("remoteImageUrls", remoteUrls);
            output.put("heightCm", input.path("heightCm").isMissingNode() ? null : input.path("heightCm").asInt());
            output.put("weightKg", input.path("weightKg").isMissingNode() ? null : input.path("weightKg").asInt());
            output.put("bodyShapeLabel", input.path("bodyShapeLabel").asText(""));
            creditBillingService.settle(taskId, new UsageActualResult(
                    "VOLCENGINE",
                    task.modelCode(),
                    UsageUnit.IMAGE,
                    null,
                    null,
                    null,
                    null,
                    remoteUrls.size(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    objectMapper.writeValueAsString(Map.of("imageCount", remoteUrls.size(), "remoteImageUrls", remoteUrls))
            ));
            taskService.completeTask(taskId, objectMapper.writeValueAsString(output));
        } catch (BusinessException ex) {
            log.warn("Avatar task {} failed: {}", taskId, ex.getMessage());
            attachRefundHint(refundIfFail[0]);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Avatar task {} error", taskId, ex);
            attachRefundHint(refundIfFail[0]);
            throw ex;
        } catch (Exception ex) {
            log.error("Avatar task {} error", taskId, ex);
            attachRefundHint(refundIfFail[0]);
            throw new com.huashuo.common.exception.RetryableException(
                    ex.getMessage() == null ? "Avatar generation unknown error" : ex.getMessage(), ex);
        }
    }

    private void attachRefundHint(boolean refundIfFail) {
        com.huashuo.task.mq.TaskFailureRefundHint.set(refundIfFail);
    }

    private String buildMeta(JsonNode input, String remoteUrl, String style, int index) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from", "avatar_generate");
        meta.put("assetRole", "host_image");
        meta.put("assetGroup", AVATAR_ASSET_GROUP);
        meta.put("avatarName", input.path("avatarName").asText(""));
        meta.put("model", imageProperties.effectiveModel());
        meta.put("size", input.path("size").asText(imageProperties.effectiveDefaultSize()));
        meta.put("style", style);
        meta.put("framing", input.path("framing").asText("FULL_BODY"));
        meta.put("outfitPreset", input.path("outfitPreset").asText(""));
        meta.put("outfitDescription", input.path("outfitDescription").asText(""));
        meta.put("heightCm", input.path("heightCm").isMissingNode() ? null : input.path("heightCm").asInt());
        meta.put("weightKg", input.path("weightKg").isMissingNode() ? null : input.path("weightKg").asInt());
        meta.put("bodyShapeLabel", input.path("bodyShapeLabel").asText(""));
        meta.put("rawPrompt", input.path("rawPrompt").asText(""));
        meta.put("imageIndex", index);
        meta.put("prompt", input.path("prompt").asText(""));
        meta.put("remoteImageUrl", remoteUrl);
        meta.put("source", "DOUBAO_SEEDREAM");
        return objectMapper.writeValueAsString(meta);
    }
}
