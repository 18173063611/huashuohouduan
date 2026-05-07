package com.huashuo.asset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.service.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.OptionalLong;

@Service
/**
 * 资产服务实现：维护项目资产列表，并为上传文件和 TTS 占位结果生成资产记录。
 */
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
    public AssetItem createUploadAsset(Long ownerUserId, Long projectId, String fileName, String filePath,
                                       String fileUrl,
                                       String mimeType, long fileSize) {
        validateProjectIfPresent(projectId);
        String assetType = detectAssetType(mimeType, fileName);
        String metadataJson = "{\"from\":\"file_upload\"}";

        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
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
        validateProjectIfPresent(projectId);
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
        entity.setOwnerUserId(null);
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
    public AssetItem createTtsAudioAsset(Long projectId, Long taskId, String fileName, String absolutePath,
                                         String previewUrl, String mimeType, long fileSize, String metadataJson) {
        validateProjectIfPresent(projectId);
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(null);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("AUDIO");
        entity.setFileName(fileName);
        entity.setFilePath(absolutePath);
        entity.setFileUrl(previewUrl);
        entity.setThumbnailUrl(null);
        entity.setMimeType(mimeType);
        entity.setFileSize(fileSize);
        entity.setSourceType("AI_GENERATED");
        entity.setMetadataJson(metadataJson == null ? "{}" : metadataJson);
        assetMapper.insert(entity);

        AssetEntity loaded = assetMapper.selectById(entity.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after insert");
        }
        return toItem(loaded);
    }

    @Override
    public AssetItem createAvatarImageAsset(Long ownerUserId, Long projectId, Long taskId, String fileName,
                                            String absolutePath,
                                            String previewUrl, String mimeType, long fileSize, String sourceType,
                                            String metadataJson) {
        validateProjectIfPresent(projectId);
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("IMAGE");
        entity.setFileName(fileName);
        entity.setFilePath(absolutePath);
        entity.setFileUrl(previewUrl);
        entity.setThumbnailUrl(previewUrl);
        entity.setMimeType(mimeType);
        entity.setFileSize(fileSize);
        entity.setSourceType(sourceType == null || sourceType.isBlank() ? "AI_GENERATED" : sourceType);
        entity.setMetadataJson(metadataJson == null ? "{}" : metadataJson);
        assetMapper.insert(entity);

        AssetEntity loaded = assetMapper.selectById(entity.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after insert");
        }
        return toItem(loaded);
    }

    @Override
    public List<AssetItem> listProjectAssets(OptionalLong viewerUserId, String listScope, Long projectId, String assetType,
                                             String keyword, String sourceType, String sort) {
        String normalizedScope = normalizeListScope(listScope);
        if ("private".equals(normalizedScope) && viewerUserId.isEmpty()) {
            return List.of();
        }
        String normalizedType = normalizeAssetType(assetType);
        String normalizedSource = normalizeSourceType(sourceType);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedSort = normalizeSort(sort);

        LambdaQueryWrapper<AssetEntity> w = new LambdaQueryWrapper<>();
        applyVisibilityScope(w, viewerUserId, normalizedScope);
        if (projectId != null) {
            validateProjectIfPresent(projectId);
            w.eq(AssetEntity::getProjectId, projectId);
        }
        if (normalizedType != null) {
            w.eq(AssetEntity::getAssetType, normalizedType);
        }
        if (normalizedSource != null) {
            w.eq(AssetEntity::getSourceType, normalizedSource);
        }
        if (normalizedKeyword != null) {
            w.apply("lower(file_name) like {0}", "%" + normalizedKeyword.toLowerCase() + "%");
        }
        applySort(w, normalizedSort);
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

    @Override
    public AssetItem getAssetForViewer(Long assetId, OptionalLong viewerUserId) {
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        assertAssetReadable(entity, viewerUserId);
        return toItem(entity);
    }

    @Override
    @Transactional
    public AssetItem saveAssetToUserCollection(Long assetId, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再保存到资产中心");
        }
        long uid = viewerUserId.getAsLong();
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        Long owner = entity.getOwnerUserId();
        if (owner != null) {
            if (!owner.equals(uid)) {
                throw new BusinessException(40300, "无权将该资产保存到您的账户");
            }
            return toItem(entity);
        }
        if ("DEMO".equalsIgnoreCase(entity.getSourceType())) {
            throw new BusinessException(40300, "演示资产不可保存为私有");
        }
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getOwnerUserId, uid)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after save");
        }
        return toItem(loaded);
    }

    @Override
    public void deleteAssetForViewer(Long assetId, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再删除资产");
        }
        AssetEntity existing = assetMapper.selectById(assetId);
        if (existing == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        Long owner = existing.getOwnerUserId();
        if (owner == null) {
            throw new BusinessException(40300, "公共或演示资产不可删除");
        }
        if (!owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40300, "无权删除该资产");
        }
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getDeleted, 1)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
    }

    private void assertAssetReadable(AssetEntity entity, OptionalLong viewerUserId) {
        Long owner = entity.getOwnerUserId();
        if (owner == null) {
            return;
        }
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        if (!owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40400, "Asset does not exist");
        }
    }

    private AssetItem toItem(AssetEntity entity) {
        return new AssetItem(
                entity.getAssetId(),
                entity.getOwnerUserId(),
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

    /**
     * @param normalizedScope {@code all}：未登录仅公共，已登录公共+本人；{@code global}：仅公共；{@code private}：仅本人（调用方已保证已登录）。
     */
    private void applyVisibilityScope(LambdaQueryWrapper<AssetEntity> w, OptionalLong viewerUserId, String normalizedScope) {
        switch (normalizedScope) {
            case "global":
                w.isNull(AssetEntity::getOwnerUserId);
                break;
            case "private":
                w.eq(AssetEntity::getOwnerUserId, viewerUserId.getAsLong());
                break;
            case "all":
            default:
                if (viewerUserId.isEmpty()) {
                    w.isNull(AssetEntity::getOwnerUserId);
                } else {
                    long uid = viewerUserId.getAsLong();
                    w.and(q -> q.isNull(AssetEntity::getOwnerUserId).or().eq(AssetEntity::getOwnerUserId, uid));
                }
        }
    }

    private String normalizeListScope(String listScope) {
        if (listScope == null || listScope.isBlank()) {
            return "all";
        }
        String s = listScope.trim().toLowerCase();
        return switch (s) {
            case "global" -> "global";
            case "private", "mine" -> "private";
            default -> "all";
        };
    }

    private void validateProjectIfPresent(Long projectId) {
        if (projectId != null) {
            projectService.getProject(projectId);
        }
    }

    private String normalizeAssetType(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            return null;
        }
        return assetType.trim().toUpperCase();
    }

    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return null;
        }
        return sourceType.trim();
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 与前端下拉选项保持一致；未知值回落到默认排序，避免拼接 order by 带来的注入风险。
     */
    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "created_at_desc";
        }
        String s = sort.trim();
        return switch (s) {
            case "createdAtDesc", "created_at_desc" -> "created_at_desc";
            case "createdAtAsc", "created_at_asc" -> "created_at_asc";
            case "fileNameAsc", "file_name_asc" -> "file_name_asc";
            case "fileSizeDesc", "file_size_desc" -> "file_size_desc";
            default -> "created_at_desc";
        };
    }

    private void applySort(LambdaQueryWrapper<AssetEntity> w, String normalizedSort) {
        if (w == null || normalizedSort == null) {
            return;
        }
        switch (normalizedSort) {
            case "created_at_asc":
                w.orderByAsc(AssetEntity::getCreatedAt).orderByAsc(AssetEntity::getAssetId);
                break;
            case "file_name_asc":
                w.orderByAsc(AssetEntity::getFileName).orderByAsc(AssetEntity::getAssetId);
                break;
            case "file_size_desc":
                w.orderByDesc(AssetEntity::getFileSize).orderByDesc(AssetEntity::getAssetId);
                break;
            case "created_at_desc":
            default:
                w.orderByDesc(AssetEntity::getCreatedAt).orderByDesc(AssetEntity::getAssetId);
                break;
        }
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
