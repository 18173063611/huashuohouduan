package com.huashuo.asset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.service.ProjectService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssetServiceImpl implements AssetService {

    private final AssetMapper assetMapper;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;

    public AssetServiceImpl(AssetMapper assetMapper, ProjectService projectService, ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssetItem createUploadAsset(Long projectId, String fileName, String filePath, String fileUrl,
                                       String mimeType, long fileSize) {
        projectService.getProject(projectId);
        String assetType = detectAssetType(mimeType, fileName);
        String metadataJson = "{\"from\":\"file_upload\"}";

        AssetEntity entity = new AssetEntity();
        entity.setProjectId(projectId);
        entity.setTaskId(null);
        entity.setAssetType(assetType);
        entity.setFileName(fileName);
        entity.setFilePath(filePath);
        entity.setFileUrl(fileUrl);
        entity.setThumbnailUrl(null);
        entity.setMimeType(mimeType);
        entity.setFileSize(fileSize);
        entity.setSourceType("USER_UPLOAD");
        entity.setMetadataJson(metadataJson);
        assetMapper.insert(entity);

        AssetEntity loaded = assetMapper.selectById(entity.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after insert");
        }
        return toItem(loaded);
    }

    @Override
    public AssetItem createMockAudioForTask(Long projectId, Long taskId, String voiceCode) {
        projectService.getProject(projectId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("voiceCode", voiceCode);
        meta.put("mock", true);
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to build asset metadata");
        }

        String safeVoice = voiceCode == null ? "default" : voiceCode.replaceAll("[^a-zA-Z0-9_-]", "_");
        AssetEntity entity = new AssetEntity();
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("AUDIO");
        entity.setFileName("mock-tts-" + taskId + ".wav");
        entity.setFilePath("/mock/audio/task-" + taskId + ".wav");
        entity.setFileUrl("/mock/audio/task-" + taskId + ".wav?voice=" + safeVoice);
        entity.setThumbnailUrl(null);
        entity.setMimeType("audio/wav");
        entity.setFileSize(1024L);
        entity.setSourceType("SYSTEM_MOCK");
        entity.setMetadataJson(metadataJson);
        assetMapper.insert(entity);

        AssetEntity loaded = assetMapper.selectById(entity.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after insert");
        }
        return toItem(loaded);
    }

    @Override
    public List<AssetItem> listProjectAssets(Long projectId, String assetType) {
        projectService.getProject(projectId);
        String normalized = normalizeAssetType(assetType);
        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        w.eq(AssetEntity::getProjectId, projectId);
        if (normalized != null) {
            w.eq(AssetEntity::getAssetType, normalized);
        }
        w.orderByDesc(AssetEntity::getCreatedAt, AssetEntity::getAssetId);
        return assetMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
    public AssetItem getAsset(Long assetId) {
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        return toItem(entity);
    }

    private AssetItem toItem(AssetEntity entity) {
        return new AssetItem(
                entity.getAssetId(),
                entity.getProjectId(),
                entity.getTaskId(),
                entity.getAssetType(),
                entity.getFileName(),
                entity.getFilePath(),
                entity.getFileUrl(),
                entity.getThumbnailUrl(),
                entity.getMimeType(),
                entity.getFileSize(),
                entity.getSourceType(),
                entity.getMetadataJson(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String normalizeAssetType(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            return null;
        }
        return assetType.trim().toUpperCase();
    }

    private String detectAssetType(String mimeType, String fileName) {
        String safeMimeType = mimeType == null ? "" : mimeType.toLowerCase();
        String safeFileName = fileName == null ? "" : fileName.toLowerCase();
        if (safeMimeType.startsWith("image/")) {
            return safeFileName.contains("cover") ? "COVER" : "IMAGE";
        }
        if (safeMimeType.startsWith("audio/")) {
            return "AUDIO";
        }
        if (safeMimeType.startsWith("video/")) {
            return "VIDEO";
        }
        if (safeMimeType.startsWith("text/") || safeFileName.endsWith(".txt") || safeFileName.endsWith(".md")) {
            return "TEXT";
        }
        if (safeMimeType.contains("json") || safeFileName.endsWith(".json")) {
            return "JSON";
        }
        return "TEXT";
    }
}
