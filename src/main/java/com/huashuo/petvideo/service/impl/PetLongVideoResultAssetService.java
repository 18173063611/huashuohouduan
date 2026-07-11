package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class PetLongVideoResultAssetService {

    private final AssetService assetService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public PetLongVideoResultAssetService(AssetService assetService,
                                          StorageService storageService,
                                          ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    public ObjectNode buildResultAssetPlan(ObjectNode manifest) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("businessDomain", "pet");
        plan.put("assetType", "PET_LONG_VIDEO_RESULT");
        plan.put("assetGroup", "视频");
        plan.put("sourceType", "PET_LONG_VIDEO_RESULT");
        plan.put("title", text(manifest, "title", "pet long video"));
        plan.put("compositionId", text(manifest, "compositionId"));
        plan.put("longVideoManifestId", longValue(manifest, "longVideoManifestId", 0L));
        plan.put("totalDurationSeconds", intValue(manifest, "totalDurationSeconds", 0));
        plan.put("aspectRatio", text(manifest, "aspectRatio", "9:16"));
        plan.put("segmentCount", manifest.withArray("segments").size());
        plan.set("sourceSegmentTaskIds", segmentTaskIds(manifest));
        plan.set("sourcePetAssetIds", sourcePetAssetIds(manifest));
        plan.put("createdForDemo", true);
        return plan;
    }

    public AssetItem saveFinalAsset(ObjectNode manifest,
                                    Path finalFile,
                                    Long ownerUserId,
                                    Long taskId,
                                    PetLongVideoStitchService.StitchResult stitchResult) {
        if (ownerUserId == null) {
            throw new BusinessException(40100, "PET_LONG_VIDEO_SAVE_FAILED: missing owner user");
        }
        if (finalFile == null || !Files.exists(finalFile)) {
            throw new BusinessException(50100, "PET_LONG_VIDEO_SAVE_FAILED: final video file does not exist");
        }
        try (InputStream in = Files.newInputStream(finalFile)) {
            String fileName = "pet-long-video-" + safeName(text(manifest, "compositionId", "composition")) + ".mp4";
            UploadResult stored = storageService.upload(in, Files.size(finalFile), fileName, "video/mp4", "video");
            return assetService.createGeneratedVideoAsset(
                    ownerUserId,
                    null,
                    taskId,
                    stored.filename(),
                    stored.objectKey(),
                    stored.url(),
                    firstThumbnail(manifest),
                    stored.contentType(),
                    stored.size(),
                    "PET_LONG_VIDEO_RESULT",
                    metadata(manifest, stored.url(), stitchResult)
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(50100, "PET_LONG_VIDEO_SAVE_FAILED: " + ex.getMessage());
        }
    }

    private String metadata(ObjectNode manifest,
                            String resultUrl,
                            PetLongVideoStitchService.StitchResult stitchResult) {
        ObjectNode metadata = buildResultAssetPlan(manifest);
        metadata.put("domain", "pet_creation");
        metadata.put("assetRole", "pet_long_video_result");
        metadata.put("resultUrl", resultUrl);
        metadata.put("estimatedCredits", longValue(manifest, "estimatedCredits", 0L));
        metadata.put("actualChargedCredits", actualChargedCredits(manifest));
        metadata.put("humanAvatarAssetId", firstCharacterAssetId(manifest, "human"));
        metadata.put("subtitleAssetId", text(manifest, "subtitleAssetId"));
        metadata.set("audioConfig", manifest.path("segments").isArray() && manifest.path("segments").size() > 0
                ? manifest.path("segments").get(0).path("providerPayload").path("diagnosticMetadata").path("audioConfig").deepCopy()
                : objectMapper.createObjectNode());
        metadata.set("stitchManifest", manifest.path("stitchManifest").deepCopy());
        metadata.set("payloadSnapshot", payloadSnapshot(manifest));
        metadata.set("segments", segmentSummary(manifest));
        metadata.put("stitchOutputDurationSeconds", stitchResult == null ? intValue(manifest, "totalDurationSeconds", 0) : stitchResult.durationSeconds());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new BusinessException(50000, "Failed to serialize pet long video asset metadata");
        }
    }

    private ArrayNode segmentTaskIds(ObjectNode manifest) {
        ArrayNode ids = objectMapper.createArrayNode();
        for (JsonNode segment : manifest.withArray("segments")) {
            long taskId = longValue(segment, "generationTaskId", 0L);
            if (taskId > 0) {
                ids.add(taskId);
            }
        }
        return ids;
    }

    private ArrayNode sourcePetAssetIds(ObjectNode manifest) {
        ArrayNode ids = objectMapper.createArrayNode();
        for (JsonNode material : manifest.withArray("sourceMaterials")) {
            String role = text(material, "role").toLowerCase(Locale.ROOT);
            if (role.contains("pet")) {
                String assetId = text(material, "assetId");
                if (StringUtils.hasText(assetId)) {
                    ids.add(assetId);
                }
            }
        }
        if (ids.isEmpty()) {
            for (JsonNode segment : manifest.withArray("segments")) {
                for (JsonNode assetId : segment.withArray("referenceAssetIds")) {
                    ids.add(assetId.asText());
                }
            }
        }
        return ids;
    }

    private ArrayNode payloadSnapshot(ObjectNode manifest) {
        ArrayNode payloads = objectMapper.createArrayNode();
        for (JsonNode segment : manifest.withArray("segments")) {
            payloads.add(segment.path("providerPayload").deepCopy());
        }
        return payloads;
    }

    private ArrayNode segmentSummary(ObjectNode manifest) {
        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode segment : manifest.withArray("segments")) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("segmentIndex", intValue(segment, "segmentIndex", summary.size() + 1));
            item.put("globalStart", intValue(segment, "globalStart", 0));
            item.put("globalEnd", intValue(segment, "globalEnd", 0));
            item.put("durationSeconds", intValue(segment, "durationSeconds", 0));
            item.put("generationTaskId", longValue(segment, "generationTaskId", 0L));
            item.put("resultUrl", text(segment, "resultUrl"));
            item.put("outputAssetId", longValue(segment, "outputAssetId", 0L));
            item.put("taskStatus", text(segment, "taskStatus"));
            summary.add(item);
        }
        return summary;
    }

    private long actualChargedCredits(ObjectNode manifest) {
        long total = 0L;
        for (JsonNode segment : manifest.withArray("segments")) {
            total += longValue(segment, "actualChargedCredits", 0L);
        }
        return total;
    }

    private String firstCharacterAssetId(ObjectNode manifest, String typeKeyword) {
        for (JsonNode character : manifest.withArray("characters")) {
            String type = text(character, "type").toLowerCase(Locale.ROOT);
            if (type.contains(typeKeyword)) {
                return text(character, "assetId");
            }
        }
        return "";
    }

    private String firstThumbnail(ObjectNode manifest) {
        for (JsonNode material : manifest.withArray("sourceMaterials")) {
            String url = text(material, "url", text(material, "fileUrl"));
            if (StringUtils.hasText(url)) {
                return url;
            }
        }
        return "";
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
}
