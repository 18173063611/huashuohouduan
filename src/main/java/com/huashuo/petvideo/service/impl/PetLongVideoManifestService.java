package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetContent;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.OptionalLong;

@Service
public class PetLongVideoManifestService {

    private static final String SOURCE_TYPE = "PET_LONG_VIDEO_MANIFEST";

    private final AssetService assetService;
    private final ObjectMapper objectMapper;

    public PetLongVideoManifestService(AssetService assetService, ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.objectMapper = objectMapper;
    }

    public ObjectNode loadManifest(Long manifestId, Long ownerUserId) {
        if (manifestId == null || manifestId <= 0) {
            throw new BusinessException(40000, "longVideoManifestId is required");
        }
        AssetContent content = assetService.getGeneratedAssetContent(manifestId, viewer(ownerUserId));
        try {
            JsonNode parsed = objectMapper.readTree(content.content());
            if (!parsed.isObject()) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: manifest content must be an object");
            }
            ObjectNode manifest = (ObjectNode) parsed;
            manifest.put("longVideoManifestId", manifestId);
            return manifest;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: " + ex.getMessage());
        }
    }

    public AssetItem createManifestAsset(ObjectNode manifest, Long ownerUserId) {
        ObjectNode metadata = metadata(manifest, null);
        AssetItem asset = assetService.createGeneratedJsonAsset(
                ownerUserId,
                null,
                null,
                manifestFileName(manifest),
                write(manifest),
                "storyboard",
                SOURCE_TYPE,
                write(metadata)
        );
        manifest.put("longVideoManifestId", asset.assetId());
        updateManifestAsset(asset.assetId(), manifest, ownerUserId);
        return assetService.getAssetForViewer(asset.assetId(), viewer(ownerUserId));
    }

    public AssetItem updateManifestAsset(Long manifestId, ObjectNode manifest, Long ownerUserId) {
        if (manifestId == null || manifestId <= 0) {
            throw new BusinessException(40000, "longVideoManifestId is required");
        }
        manifest.put("longVideoManifestId", manifestId);
        return assetService.updateEditableTextAsset(
                manifestId,
                manifestFileName(manifest),
                write(manifest),
                write(metadata(manifest, manifestId)),
                viewer(ownerUserId)
        );
    }

    public ObjectNode ensurePersisted(ObjectNode manifest, Long ownerUserId) {
        Long id = longValue(manifest, "longVideoManifestId");
        if (id == null) {
            id = longValue(manifest, "manifestAssetId");
        }
        if (id != null && id > 0) {
            updateManifestAsset(id, manifest, ownerUserId);
            return manifest;
        }
        createManifestAsset(manifest, ownerUserId);
        return manifest;
    }

    private ObjectNode metadata(ObjectNode manifest, Long manifestId) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("businessDomain", "pet");
        metadata.put("domain", "pet_creation");
        metadata.put("assetRole", "pet_story_video_composition");
        metadata.put("sourceRole", "pet_long_video_manifest");
        metadata.put("assetGroup", "分镜");
        metadata.put("assetType", "PET_LONG_VIDEO_MANIFEST");
        metadata.put("templateType", text(manifest, "templateType", "PET_STORY_LONG_VIDEO"));
        metadata.put("title", text(manifest, "title", "pet long video"));
        metadata.put("compositionId", text(manifest, "compositionId"));
        metadata.put("totalDurationSeconds", intValue(manifest, "totalDurationSeconds", 0));
        metadata.put("segmentCount", intValue(manifest, "segmentCount", manifest.withArray("segments").size()));
        metadata.put("estimatedCredits", longValue(manifest, "estimatedCredits", 0L));
        metadata.put("createdForDemo", true);
        if (manifestId != null) {
            metadata.put("longVideoManifestId", manifestId);
        }
        return metadata;
    }

    private String manifestFileName(ObjectNode manifest) {
        String compositionId = text(manifest, "compositionId", "composition");
        String manifestId = text(manifest, "longVideoManifestId", "draft");
        return "pet-long-video-manifest-" + safeName(compositionId) + "-" + safeName(manifestId) + ".json";
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new BusinessException(50000, "Failed to serialize pet long video manifest");
        }
    }

    private OptionalLong viewer(Long ownerUserId) {
        return ownerUserId == null ? OptionalLong.empty() : OptionalLong.of(ownerUserId);
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).canConvertToLong()) {
            return null;
        }
        return node.get(field).asLong();
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        Long value = longValue(node, field);
        return value == null ? fallback : value;
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : fallback;
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
        String safe = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return StringUtils.hasText(safe) ? safe : "unknown";
    }
}
