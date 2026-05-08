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
import com.huashuo.storage.resolve.StoredUrlResolver;
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
    private final ObjectMapper objectMapper;
    private final StoredUrlResolver storedUrlResolver;

    private static final String VISIBILITY_PUBLIC = "PUBLIC";
    private static final String VISIBILITY_PRIVATE = "PRIVATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING_SAVE = "PENDING_SAVE";
    private static final String STATUS_REMOVED = "REMOVED";

    public AssetServiceImpl(AssetMapper assetMapper, ObjectMapper objectMapper, StoredUrlResolver storedUrlResolver) {
        this.assetMapper = assetMapper;
        this.objectMapper = objectMapper;
        this.storedUrlResolver = storedUrlResolver;
    }

    @Override
    public AssetItem createUploadAsset(Long ownerUserId, Long projectId, String fileName, String filePath,
                                       String fileUrl,
                                       String mimeType, long fileSize) {
        String assetType = detectAssetType(mimeType, fileName);
        String metadataJson = "{\"from\":\"file_upload\"}";

        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setCreatedByUserId(ownerUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(null);
        entity.setAssetType(assetType);
        entity.setKind("MATERIAL");
        entity.setVisibility(ownerUserId == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(ownerUserId == null ? LocalDateTime.now() : null);
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
        entity.setCreatedByUserId(null);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("AUDIO");
        entity.setKind("GENERATED");
        entity.setVisibility(VISIBILITY_PUBLIC);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(LocalDateTime.now());
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
    public AssetItem createTtsAudioAsset(Long createdByUserId, Long projectId, Long taskId, String fileName, String absolutePath,
                                         String previewUrl, String mimeType, long fileSize, String metadataJson) {
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(null);
        entity.setCreatedByUserId(createdByUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("AUDIO");
        entity.setKind("GENERATED");
        entity.setVisibility(VISIBILITY_PUBLIC);
        entity.setStatus(STATUS_PENDING_SAVE);
        entity.setPublishedAt(null);
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
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setCreatedByUserId(ownerUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("IMAGE");
        entity.setKind("GENERATED");
        entity.setVisibility(ownerUserId == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(ownerUserId == null ? LocalDateTime.now() : null);
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
    public AssetItem createGeneratedVideoAsset(Long ownerUserId, Long projectId, Long taskId, String fileName,
                                               String absolutePath,
                                               String previewUrl, String thumbnailUrl, String mimeType, long fileSize,
                                               String sourceType, String metadataJson) {
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setCreatedByUserId(ownerUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("VIDEO");
        entity.setKind("GENERATED");
        entity.setVisibility(ownerUserId == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(ownerUserId == null ? LocalDateTime.now() : null);
        entity.setFileName(fileName);
        entity.setFilePath(absolutePath);
        entity.setFileUrl(previewUrl);
        entity.setThumbnailUrl(thumbnailUrl);
        entity.setMimeType(mimeType == null || mimeType.isBlank() ? "video/mp4" : mimeType);
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
        w.eq(AssetEntity::getStatus, STATUS_ACTIVE);
        if (projectId != null) {
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
        boolean pendingGenerated = STATUS_PENDING_SAVE.equalsIgnoreCase(safeStatus(entity));
        if (pendingGenerated) {
            Long createdBy = entity.getCreatedByUserId();
            if (createdBy != null && !createdBy.equals(uid)) {
                throw new BusinessException(40300, "Cannot save this asset");
            }
        } else {
            assertAssetReadable(entity, viewerUserId);
        }
        if ("DEMO".equalsIgnoreCase(entity.getSourceType())) {
            throw new BusinessException(40300, "演示资产不可保存为私有");
        }
        if (VISIBILITY_PRIVATE.equalsIgnoreCase(safeVisibility(entity))) {
            Long owner = entity.getOwnerUserId();
            if (owner == null || !owner.equals(uid)) {
                throw new BusinessException(40300, "无权将该资产保存到您的账户");
            }
            return toItem(entity);
        }
        // 公共资产保存为私有：复制一份到当前用户下，避免影响公共池。
        AssetEntity copy = new AssetEntity();
        copy.setOwnerUserId(uid);
        copy.setCreatedByUserId(uid);
        copy.setProjectId(entity.getProjectId());
        copy.setTaskId(entity.getTaskId());
        copy.setAssetType(entity.getAssetType());
        copy.setKind(entity.getKind() == null || entity.getKind().isBlank() ? "MATERIAL" : entity.getKind());
        copy.setVisibility(VISIBILITY_PRIVATE);
        copy.setStatus(STATUS_ACTIVE);
        copy.setPublishedAt(null);
        copy.setFileName(entity.getFileName());
        copy.setFilePath(entity.getFilePath());
        copy.setFileUrl(entity.getFileUrl());
        copy.setThumbnailUrl(entity.getThumbnailUrl());
        copy.setMimeType(entity.getMimeType());
        copy.setFileSize(entity.getFileSize());
        copy.setSourceType(entity.getSourceType());
        copy.setMetadataJson(appendMetadata(entity.getMetadataJson(), "forkFromAssetId", entity.getAssetId()));
        assetMapper.insert(copy);
        AssetEntity loaded = assetMapper.selectById(copy.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after save");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public AssetItem publishAsset(Long assetId, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再发布到公共资产");
        }
        long uid = viewerUserId.getAsLong();
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        // 仅允许发布本人私有资产
        if (!VISIBILITY_PRIVATE.equalsIgnoreCase(safeVisibility(entity))) {
            return toItem(entity);
        }
        if (entity.getOwnerUserId() == null || !entity.getOwnerUserId().equals(uid)) {
            throw new BusinessException(40300, "无权发布该资产");
        }
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getVisibility, VISIBILITY_PUBLIC)
                .set(AssetEntity::getPublishedAt, LocalDateTime.now())
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after publish");
        }
        // 若缺少创建者信息，补齐为当前用户，便于公共资产作者展示
        if (loaded.getCreatedByUserId() == null) {
            LambdaUpdateWrapper<AssetEntity> uw2 = new LambdaUpdateWrapper<>();
            uw2.eq(AssetEntity::getAssetId, assetId)
                    .set(AssetEntity::getCreatedByUserId, uid)
                    .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
            assetMapper.update(null, uw2);
            loaded = assetMapper.selectById(assetId);
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public AssetItem unpublishAsset(Long assetId, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再下架公共资产");
        }
        long uid = viewerUserId.getAsLong();
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        if (!VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(entity))) {
            throw new BusinessException(40000, "仅公共资产可下架");
        }
        Long createdBy = entity.getCreatedByUserId();
        if (createdBy == null || !createdBy.equals(uid)) {
            throw new BusinessException(40300, "无权下架该资产");
        }
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getStatus, STATUS_REMOVED)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after unpublish");
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
        // 公共资产不提供 DELETE（使用下架接口）；这里保持兼容：公共直接拒绝。
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(existing))) {
            throw new BusinessException(40300, "公共资产不可删除，可使用下架");
        }
        if (owner == null || !owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40300, "无权删除该资产");
        }
        LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getDeleted, 1)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, uw);
    }

    private void assertAssetReadable(AssetEntity entity, OptionalLong viewerUserId) {
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        String visibility = safeVisibility(entity);
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(visibility)) {
            if (!STATUS_ACTIVE.equalsIgnoreCase(safeStatus(entity))) {
                throw new BusinessException(40400, "Asset does not exist");
            }
            return;
        }
        Long owner = entity.getOwnerUserId();
        if (viewerUserId.isEmpty() || owner == null || !owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40400, "Asset does not exist");
        }
    }

    private AssetItem toItem(AssetEntity entity) {
        String fileUrl = storedUrlResolver.resolveToPublicUrl(entity.getFileUrl());
        String thumb = entity.getThumbnailUrl() == null ? null : storedUrlResolver.resolveToPublicUrl(entity.getThumbnailUrl());
        return new AssetItem(
                entity.getAssetId(),
                entity.getOwnerUserId(),
                entity.getCreatedByUserId(),
                entity.getProjectId(),
                entity.getTaskId(),
                entity.getAssetType(),
                entity.getKind(),
                safeVisibility(entity),
                safeStatus(entity),
                entity.getPublishedAt(),
                entity.getFileName(),
                entity.getFilePath(),
                fileUrl,
                thumb,
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
                w.eq(AssetEntity::getVisibility, VISIBILITY_PUBLIC);
                break;
            case "private":
                w.eq(AssetEntity::getVisibility, VISIBILITY_PRIVATE)
                        .eq(AssetEntity::getOwnerUserId, viewerUserId.getAsLong());
                break;
            case "all":
            default:
                if (viewerUserId.isEmpty()) {
                    w.eq(AssetEntity::getVisibility, VISIBILITY_PUBLIC);
                } else {
                    long uid = viewerUserId.getAsLong();
                    w.and(q -> q.eq(AssetEntity::getVisibility, VISIBILITY_PUBLIC)
                            .or()
                            .eq(AssetEntity::getVisibility, VISIBILITY_PRIVATE).eq(AssetEntity::getOwnerUserId, uid));
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
                // 公共资产更偏向“发布”时间；私有资产仍使用创建时间。为了兼容 MyBatis-Plus lambda，优先 publishedAt，其次 createdAt。
                w.orderByDesc(AssetEntity::getPublishedAt).orderByDesc(AssetEntity::getCreatedAt).orderByDesc(AssetEntity::getAssetId);
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

    private String safeVisibility(AssetEntity entity) {
        if (entity == null) {
            return VISIBILITY_PRIVATE;
        }
        if (entity.getVisibility() != null && !entity.getVisibility().isBlank()) {
            return entity.getVisibility().trim().toUpperCase();
        }
        // 兼容历史：owner 为空视为公共
        return entity.getOwnerUserId() == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE;
    }

    private String safeStatus(AssetEntity entity) {
        if (entity == null) {
            return STATUS_ACTIVE;
        }
        if (entity.getStatus() != null && !entity.getStatus().isBlank()) {
            return entity.getStatus().trim().toUpperCase();
        }
        return STATUS_ACTIVE;
    }

    private String appendMetadata(String metadataJson, String key, Object value) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(metadataJson, Map.class);
                if (parsed != null) {
                    meta.putAll(parsed);
                }
            } catch (Exception ignored) {
                // ignore invalid metadata
            }
        }
        meta.put(key, value);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return metadataJson == null ? "{}" : metadataJson;
        }
    }
}
