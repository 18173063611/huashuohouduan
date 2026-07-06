package com.huashuo.asset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetContent;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.storage.resolve.StoredUrlResolver;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

@Service
/**
 * 资产服务实现：维护项目资产列表，并为上传文件和 TTS 占位结果生成资产记录。
 */
public class AssetServiceImpl implements AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetServiceImpl.class);

    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;
    private final StoredUrlResolver storedUrlResolver;
    private final StorageService storageService;
    private final TaskMapper taskMapper;
    private final AdminAccessService adminAccessService;

    private static final String VISIBILITY_PUBLIC = "PUBLIC";
    private static final String VISIBILITY_PRIVATE = "PRIVATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING_SAVE = "PENDING_SAVE";
    private static final String STATUS_REMOVED = "REMOVED";
    private static final String GROUP_UNGROUPED_FILTER = "__ungrouped";
    private static final String GROUP_CAR_MODEL_BUNDLE = "汽车素材包";
    private static final String GROUP_BENCHMARK = "爆款对标";
    private static final String GROUP_STORYBOARD = "分镜脚本";
    private static final String GROUP_LEGACY_SCRIPT = "口播文案";
    private static final String GROUP_LEGACY_COPY_ASSET = "文案资产";
    private static final String GROUP_LEGACY_STORYBOARD_ASSET = "分镜资产";
    private static final HttpClient CONTENT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AssetServiceImpl(AssetMapper assetMapper, ObjectMapper objectMapper, StoredUrlResolver storedUrlResolver,
                            StorageService storageService, TaskMapper taskMapper,
                            AdminAccessService adminAccessService) {
        this.assetMapper = assetMapper;
        this.objectMapper = objectMapper;
        this.storedUrlResolver = storedUrlResolver;
        this.storageService = storageService;
        this.taskMapper = taskMapper;
        this.adminAccessService = adminAccessService;
    }

    @Override
    public AssetItem createUploadAsset(Long ownerUserId, Long projectId, String fileName, String filePath,
                                       String fileUrl,
                                       String mimeType, long fileSize) {
        return createUploadAsset(ownerUserId, projectId, fileName, filePath, fileUrl, mimeType, fileSize, null);
    }

    @Override
    public AssetItem createUploadAsset(Long ownerUserId, Long projectId, String fileName, String filePath,
                                       String fileUrl,
                                       String mimeType, long fileSize, String metadataJson) {
        String assetType = detectAssetType(mimeType, fileName);
        String safeMetadataJson = StringUtils.hasText(metadataJson) ? metadataJson.trim() : "{\"from\":\"file_upload\"}";

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
        entity.setThumbnailUrl(initialThumbnailUrl(assetType, fileUrl, safeMetadataJson));
        entity.setMimeType(mimeType);
        entity.setFileSize(fileSize);
        entity.setSourceType("USER_UPLOAD");
        entity.setAssetGroup(inferAssetGroup(safeMetadataJson, assetType, "USER_UPLOAD"));
        entity.setMetadataJson(safeMetadataJson);
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
        entity.setAssetGroup(inferAssetGroup(metadataJson, "AUDIO", "SYSTEM_MOCK"));
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
        return createTtsAudioAsset(createdByUserId, projectId, taskId, fileName, absolutePath, previewUrl, mimeType,
                fileSize, "TTS_GENERATE", metadataJson);
    }

    @Override
    public AssetItem createTtsAudioAsset(Long createdByUserId, Long projectId, Long taskId, String fileName, String absolutePath,
                                         String previewUrl, String mimeType, long fileSize, String sourceType,
                                         String metadataJson) {
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(createdByUserId);
        entity.setCreatedByUserId(createdByUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("AUDIO");
        entity.setKind("GENERATED");
        entity.setVisibility(createdByUserId == null ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(createdByUserId == null ? LocalDateTime.now() : null);
        entity.setFileName(fileName);
        entity.setFilePath(absolutePath);
        entity.setFileUrl(previewUrl);
        entity.setThumbnailUrl(null);
        entity.setMimeType(mimeType);
        entity.setFileSize(fileSize);
        String safeSourceType = StringUtils.hasText(sourceType) ? sourceType.trim() : "TTS_GENERATE";
        String safeMetadata = metadataJson == null ? "{}" : metadataJson;
        entity.setSourceType(safeSourceType);
        entity.setAssetGroup(inferAssetGroup(safeMetadata, "AUDIO", safeSourceType));
        entity.setMetadataJson(safeMetadata);
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
        String safeSourceType = sourceType == null || sourceType.isBlank() ? "AI_GENERATED" : sourceType.trim();
        String safeMetadata = metadataJson == null ? "{}" : metadataJson;
        entity.setSourceType(safeSourceType);
        entity.setAssetGroup(inferAssetGroup(safeMetadata, "IMAGE", safeSourceType));
        entity.setMetadataJson(safeMetadata);
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
        if (ownerUserId == null) {
            throw new BusinessException(40100, "生成视频保存到私有资产失败：缺少登录用户信息");
        }
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setCreatedByUserId(ownerUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("VIDEO");
        entity.setKind("GENERATED");
        entity.setVisibility(VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(null);
        entity.setFileName(fileName);
        entity.setFilePath(absolutePath);
        entity.setFileUrl(previewUrl);
        entity.setMimeType(mimeType == null || mimeType.isBlank() ? "video/mp4" : mimeType);
        entity.setFileSize(Math.max(0L, fileSize));
        String safeSourceType = sourceType == null || sourceType.isBlank() ? "AI_GENERATED" : sourceType.trim();
        String safeMetadata = metadataJson == null ? "{}" : metadataJson;
        String safeThumbnailUrl = firstNonBlank(
                thumbnailUrl,
                metadataText(safeMetadata, "firstFrameUrl"),
                metadataText(safeMetadata, "coverUrl"),
                metadataText(safeMetadata, "thumbnailUrl"),
                metadataText(safeMetadata, "posterUrl"),
                metadataUrlAt(safeMetadata, "/input/carImageUrls/0"),
                metadataUrlAt(safeMetadata, "/input/scene/referenceImage"),
                metadataUrlAt(safeMetadata, "/input/scene/imageUrls/0"),
                metadataUrlAt(safeMetadata, "/input/segmentRequest/imageUrl"),
                metadataUrlAt(safeMetadata, "/assetRoleBindings/0/url"),
                metadataUrlAt(safeMetadata, "/input/seedanceDiagnostics/assetRoleBindings/0/url"),
                metadataUrlAt(safeMetadata, "/segmentVideos/0/firstFrameUrl")
        );
        if (StringUtils.hasText(safeThumbnailUrl)) {
            safeMetadata = appendMetadata(safeMetadata, "firstFrameUrl", safeThumbnailUrl);
            safeMetadata = appendMetadata(safeMetadata, "coverUrl", safeThumbnailUrl);
            safeMetadata = appendMetadata(safeMetadata, "thumbnailUrl", safeThumbnailUrl);
        }
        entity.setSourceType(safeSourceType);
        entity.setAssetGroup(inferAssetGroup(safeMetadata, "VIDEO", safeSourceType));
        entity.setMetadataJson(safeMetadata);
        entity.setThumbnailUrl(safeThumbnailUrl);
        assetMapper.insert(entity);

        AssetEntity loaded = assetMapper.selectById(entity.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after insert");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public AssetItem createGeneratedJsonAsset(Long ownerUserId, Long projectId, Long taskId, String fileName,
                                              String jsonContent, String storageCategory, String sourceType,
                                              String metadataJson) {
        if (ownerUserId == null) {
            throw new BusinessException(40100, "生成产物保存到私有资产失败：缺少登录用户信息");
        }
        String content = StringUtils.hasText(jsonContent) ? jsonContent : "{}";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String safeFileName = ensureJsonFileName(fileName, taskId);
        String category = StringUtils.hasText(storageCategory) ? storageCategory.trim() : "storyboard";

        UploadResult stored = null;
        try {
            stored = storageService.upload(
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    safeFileName,
                    "application/json",
                    category
            );
        } catch (RuntimeException ex) {
            log.warn("Generated JSON asset falls back to task output. taskId={}, fileName={}, reason={}",
                    taskId, safeFileName, ex.getMessage());
        }

        boolean fallbackToTaskOutput = stored == null;
        AssetEntity entity = new AssetEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setCreatedByUserId(ownerUserId);
        entity.setProjectId(projectId);
        entity.setTaskId(taskId);
        entity.setAssetType("JSON");
        entity.setKind("GENERATED");
        entity.setVisibility(VISIBILITY_PRIVATE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setPublishedAt(null);
        entity.setFileName(stored == null ? safeFileName : stored.filename());
        entity.setFilePath(stored == null ? "task-output:" + taskId : stored.objectKey());
        entity.setFileUrl(stored == null ? "/api/v1/assets/pending/content" : stored.url());
        entity.setThumbnailUrl(null);
        entity.setMimeType(stored == null ? "application/json" : stored.contentType());
        entity.setFileSize(stored == null ? (long) bytes.length : stored.size());
        String safeSourceType = StringUtils.hasText(sourceType) ? sourceType.trim() : "AI_GENERATED";
        entity.setSourceType(safeSourceType);
        String meta = appendMetadata(metadataJson == null ? "{}" : metadataJson,
                "storageMode", fallbackToTaskOutput ? "TASK_OUTPUT" : "OBJECT_STORAGE");
        meta = appendMetadata(meta, "contentLength", bytes.length);
        entity.setAssetGroup(inferAssetGroup(meta, "JSON", safeSourceType));
        entity.setMetadataJson(meta);
        assetMapper.insert(entity);

        if (fallbackToTaskOutput) {
            LambdaUpdateWrapper<AssetEntity> uw = new LambdaUpdateWrapper<>();
            uw.eq(AssetEntity::getAssetId, entity.getAssetId())
                    .set(AssetEntity::getFileUrl, "/api/v1/assets/" + entity.getAssetId() + "/content")
                    .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
            assetMapper.update(null, uw);
        }

        AssetEntity loaded = assetMapper.selectById(entity.getAssetId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after insert");
        }
        return toItem(loaded);
    }

    @Override
    public List<AssetItem> listProjectAssets(OptionalLong viewerUserId, String listScope, Long projectId, String assetType,
                                             String keyword, String sourceType, String assetGroup, String sort,
                                             Integer pageNo, Integer pageSize, Boolean includePreview, String businessDomain) {
        String normalizedScope = normalizeListScope(listScope);
        if ("private".equals(normalizedScope) && viewerUserId.isEmpty()) {
            return List.of();
        }
        String normalizedType = normalizeAssetType(assetType);
        String normalizedSource = normalizeSourceType(sourceType);
        String normalizedGroup = normalizeAssetGroupFilter(assetGroup);
        String normalizedBusinessDomain = normalizeBusinessDomain(businessDomain);
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
        if (normalizedGroup != null) {
            if (GROUP_UNGROUPED_FILTER.equals(normalizedGroup)) {
                w.and(q -> q.isNull(AssetEntity::getAssetGroup).or().eq(AssetEntity::getAssetGroup, ""));
            } else if (GROUP_BENCHMARK.equals(normalizedGroup)) {
                w.and(q -> q.eq(AssetEntity::getAssetGroup, GROUP_BENCHMARK)
                        .or().eq(AssetEntity::getAssetGroup, GROUP_LEGACY_SCRIPT)
                        .or().eq(AssetEntity::getAssetGroup, GROUP_LEGACY_COPY_ASSET));
            } else if (GROUP_STORYBOARD.equals(normalizedGroup)) {
                w.and(q -> q.eq(AssetEntity::getAssetGroup, GROUP_STORYBOARD)
                        .or().eq(AssetEntity::getAssetGroup, GROUP_LEGACY_STORYBOARD_ASSET));
            } else {
                w.eq(AssetEntity::getAssetGroup, normalizedGroup);
            }
        }
        applyBusinessDomainFilter(w, normalizedBusinessDomain);
        applyKeywordFilter(w, normalizedKeyword);
        excludePublicCarModelBundleComponentImages(w);
        applyViewerFirstSort(w, viewerUserId, normalizedScope);
        applySort(w, normalizedSort);
        applyPagination(w, pageNo, pageSize);
        boolean shouldIncludePreview = includePreview == null || includePreview;
        return assetMapper.selectList(w).stream()
                .filter(entity -> !shouldHidePublicCarModelBundleComponent(entity))
                .map(entity -> toItem(entity, shouldIncludePreview))
                .toList();
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
    public AssetContent getGeneratedAssetContent(Long assetId, OptionalLong viewerUserId) {
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        assertAssetReadable(entity, viewerUserId);
        if (!StringUtils.hasText(entity.getFilePath()) || !entity.getFilePath().startsWith("task-output:")) {
            if (!isTextPreviewableAsset(entity)) {
                throw new BusinessException(40000, "该资产内容已存储为文件，请直接打开 fileUrl");
            }
            return new AssetContent(
                    entity.getFileName(),
                    previewContentType(entity),
                    fetchStoredTextContent(entity)
            );
        }
        if (entity.getTaskId() == null) {
            throw new BusinessException(40400, "Asset content does not exist");
        }
        TaskEntity task = taskMapper.selectById(entity.getTaskId());
        if (task == null || !StringUtils.hasText(task.getOutputJson())) {
            throw new BusinessException(40400, "Asset content does not exist");
        }
        return new AssetContent(
                entity.getFileName(),
                StringUtils.hasText(entity.getMimeType()) ? entity.getMimeType() : "application/json",
                task.getOutputJson()
        );
    }

    private boolean isTextPreviewableAsset(AssetEntity entity) {
        String assetType = entity.getAssetType() == null ? "" : entity.getAssetType().trim().toUpperCase();
        String mimeType = entity.getMimeType() == null ? "" : entity.getMimeType().trim().toLowerCase();
        String fileName = entity.getFileName() == null ? "" : entity.getFileName().trim().toLowerCase();
        return "JSON".equals(assetType)
                || "TEXT".equals(assetType)
                || mimeType.contains("json")
                || mimeType.startsWith("text/")
                || fileName.endsWith(".json")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".md");
    }

    private String previewContentType(AssetEntity entity) {
        if (StringUtils.hasText(entity.getMimeType())) {
            return entity.getMimeType();
        }
        String assetType = entity.getAssetType() == null ? "" : entity.getAssetType().trim().toUpperCase();
        return "JSON".equals(assetType) ? "application/json" : "text/plain; charset=UTF-8";
    }

    private String fetchStoredTextContent(AssetEntity entity) {
        String url = storedUrlResolver.resolveToPublicUrl(entity.getFileUrl());
        if (!StringUtils.hasText(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BusinessException(40400, "Asset content does not exist");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = CONTENT_HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50200, "Generated asset content fetch failed, HTTP " + response.statusCode());
            }
            return response.body();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(50200, "Generated asset content fetch failed: " + ex.getMessage());
        }
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
        copy.setAssetGroup(entity.getAssetGroup());
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
    @Transactional
    public AssetItem updateAssetGroup(Long assetId, String assetGroup, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再管理资产分组");
        }
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        long uid = viewerUserId.getAsLong();
        String visibility = safeVisibility(entity);
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(visibility)) {
            if (!adminAccessService.isAdmin(uid)) {
                throw new BusinessException(40300, "公共资产分组仅管理员可管理");
            }
        } else {
            Long owner = entity.getOwnerUserId();
            if (owner == null || !owner.equals(uid)) {
                throw new BusinessException(40300, "无权管理该私有资产分组");
            }
        }

        String normalizedGroup = normalizeAssetGroupRequired(assetGroup);
        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getAssetGroup, normalizedGroup)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, update);
        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after group update");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public AssetItem updateAssetCover(Long assetId, String thumbnailUrl, String metadataJson,
                                      OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再设置资产封面");
        }
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        long uid = viewerUserId.getAsLong();
        String visibility = safeVisibility(entity);
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(visibility)) {
            if (!adminAccessService.isAdmin(uid)) {
                throw new BusinessException(40300, "公共资产封面仅管理员可管理");
            }
        } else {
            Long owner = entity.getOwnerUserId();
            if (owner == null || !owner.equals(uid)) {
                throw new BusinessException(40300, "无权设置该私有资产封面");
            }
        }

        String safeThumbnailUrl = StringUtils.hasText(thumbnailUrl) ? thumbnailUrl.trim() : null;
        if (!StringUtils.hasText(safeThumbnailUrl)) {
            throw new BusinessException(40000, "封面地址不能为空");
        }

        String safeMetadata = mergeMetadataJson(entity.getMetadataJson(), metadataJson);
        safeMetadata = appendMetadata(safeMetadata, "coverUrl", safeThumbnailUrl);
        safeMetadata = appendMetadata(safeMetadata, "thumbnailUrl", safeThumbnailUrl);
        safeMetadata = appendMetadata(safeMetadata, "coverUpdatedAt", LocalDateTime.now().toString());

        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getThumbnailUrl, safeThumbnailUrl)
                .set(AssetEntity::getMetadataJson, safeMetadata)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, update);

        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after cover update");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public AssetItem updateCarModelBundle(Long assetId, String fileName, String contentJson, String metadataJson,
                                          OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再编辑车型素材包");
        }
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        if (!isCarModelBundleAsset(entity)) {
            throw new BusinessException(40000, "该资产不是车型素材包");
        }
        assertCarModelBundleWritable(entity, viewerUserId.getAsLong());

        String content = StringUtils.hasText(contentJson) ? contentJson.trim() : null;
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(40000, "车型素材包内容不能为空");
        }
        JsonNode root = parseJson(content);
        if (!isCarModelBundlePayload(root)) {
            throw new BusinessException(40000, "车型素材包内容格式不正确");
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String displayFileName = normalizeJsonDisplayFileName(fileName, entity.getFileName(), assetId);
        UploadResult stored = storageService.upload(
                new ByteArrayInputStream(bytes),
                bytes.length,
                displayFileName,
                "application/json",
                "upload"
        );

        String safeMetadata = StringUtils.hasText(metadataJson) ? metadataJson.trim() : "{}";
        safeMetadata = appendMetadata(safeMetadata, "from", "car_model_bundle");
        safeMetadata = appendMetadata(safeMetadata, "assetRole", "car_model_bundle");
        safeMetadata = appendMetadata(safeMetadata, "assetGroup", GROUP_CAR_MODEL_BUNDLE);
        safeMetadata = appendMetadata(safeMetadata, "bundleType", "car_model");
        safeMetadata = appendMetadata(safeMetadata, "contentLength", bytes.length);
        String bundleCoverUrl = firstNonBlank(
                metadataText(safeMetadata, "coverUrl"),
                textAt(root, "/coverUrl"),
                firstBundleImageUrl(root)
        );
        int bundleImageCount = countBundleImages(root);
        if (StringUtils.hasText(bundleCoverUrl)) {
            safeMetadata = appendMetadata(safeMetadata, "coverUrl", bundleCoverUrl);
            safeMetadata = appendMetadata(safeMetadata, "thumbnailUrl", bundleCoverUrl);
        }
        if (bundleImageCount > 0) {
            safeMetadata = appendMetadata(safeMetadata, "imageCount", bundleImageCount);
            safeMetadata = appendMetadata(safeMetadata, "componentCount", bundleImageCount);
        }

        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getAssetType, "JSON")
                .set(AssetEntity::getKind, StringUtils.hasText(entity.getKind()) ? entity.getKind() : "MATERIAL")
                .set(AssetEntity::getFileName, displayFileName)
                .set(AssetEntity::getFilePath, stored.objectKey())
                .set(AssetEntity::getFileUrl, stored.url())
                .set(AssetEntity::getThumbnailUrl, bundleCoverUrl)
                .set(AssetEntity::getMimeType, stored.contentType())
                .set(AssetEntity::getFileSize, stored.size())
                .set(AssetEntity::getAssetGroup, inferAssetGroup(safeMetadata, "JSON", entity.getSourceType()))
                .set(AssetEntity::getMetadataJson, safeMetadata)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, update);
        markCarModelBundleComponentAssets(root, viewerUserId.getAsLong());

        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after bundle update");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public void hideCarModelBundleComponentAssets(String contentJson, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty() || !StringUtils.hasText(contentJson)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            if (isCarModelBundlePayload(root)) {
                markCarModelBundleComponentAssets(root, viewerUserId.getAsLong());
            }
        } catch (Exception ignored) {
            // Bundle upload itself should not fail just because component cleanup cannot parse legacy JSON.
        }
    }

    @Override
    @Transactional
    public AssetItem updateEditableTextAsset(Long assetId, String fileName, String content, String metadataJson,
                                             OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            throw new BusinessException(40100, "请先登录后再编辑资产内容");
        }
        AssetEntity entity = assetMapper.selectById(assetId);
        if (entity == null) {
            throw new BusinessException(40400, "Asset does not exist");
        }
        assertEditableTextAssetWritable(entity, viewerUserId.getAsLong());

        String safeContent = StringUtils.hasText(content) ? content.trim() : "";
        if (!StringUtils.hasText(safeContent)) {
            throw new BusinessException(40000, "资产内容不能为空");
        }
        String assetType = safeAssetType(entity);
        if ("JSON".equals(assetType)) {
            parseJson(safeContent);
        }

        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        String displayFileName = normalizeEditableTextFileName(fileName, entity.getFileName(), assetId, assetType);
        String contentType = "JSON".equals(assetType) ? "application/json" : "text/plain";
        String category = isStoryboardAsset(entity) ? "storyboard" : "writer";
        UploadResult stored = storageService.upload(
                new ByteArrayInputStream(bytes),
                bytes.length,
                displayFileName,
                contentType,
                category
        );

        String safeMetadata = StringUtils.hasText(metadataJson) ? metadataJson.trim() : entity.getMetadataJson();
        safeMetadata = StringUtils.hasText(safeMetadata) ? safeMetadata : "{}";
        safeMetadata = appendMetadata(safeMetadata, "contentLength", bytes.length);
        String thumbnailUrl = firstNonBlank(
                metadataText(safeMetadata, "coverUrl"),
                metadataText(safeMetadata, "thumbnailUrl"),
                metadataText(safeMetadata, "firstFrameUrl")
        );

        LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AssetEntity::getAssetId, assetId)
                .set(AssetEntity::getFileName, displayFileName)
                .set(AssetEntity::getFilePath, stored.objectKey())
                .set(AssetEntity::getFileUrl, stored.url())
                .set(AssetEntity::getThumbnailUrl, thumbnailUrl)
                .set(AssetEntity::getMimeType, stored.contentType())
                .set(AssetEntity::getFileSize, stored.size())
                .set(AssetEntity::getAssetGroup, inferAssetGroup(safeMetadata, assetType, entity.getSourceType()))
                .set(AssetEntity::getMetadataJson, safeMetadata)
                .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
        assetMapper.update(null, update);

        AssetEntity loaded = assetMapper.selectById(assetId);
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load asset after content update");
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

    private void assertEditableTextAssetWritable(AssetEntity entity, long uid) {
        if (!isEditableTextAsset(entity)) {
            throw new BusinessException(40000, "仅支持编辑爆款对标和分镜脚本内容");
        }
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(entity))) {
            if (!adminAccessService.isAdmin(uid)) {
                throw new BusinessException(40300, "仅管理员可编辑公共资产内容");
            }
            return;
        }
        if (adminAccessService.isAdmin(uid)) {
            return;
        }
        Long owner = entity.getOwnerUserId();
        if (owner == null || !owner.equals(uid)) {
            throw new BusinessException(40300, "无权编辑该私有资产内容");
        }
    }

    private boolean isEditableTextAsset(AssetEntity entity) {
        String type = safeAssetType(entity);
        return ("TEXT".equals(type) || "JSON".equals(type))
                && (isBenchmarkAsset(entity) || isStoryboardAsset(entity));
    }

    private boolean isBenchmarkAsset(AssetEntity entity) {
        String group = entity.getAssetGroup() == null ? "" : entity.getAssetGroup().trim();
        String source = entity.getSourceType() == null ? "" : entity.getSourceType().trim().toUpperCase();
        String role = metadataText(entity.getMetadataJson(), "assetRole");
        String normalizedRole = role == null ? "" : role.trim().toLowerCase();
        String fileName = entity.getFileName() == null ? "" : entity.getFileName().toLowerCase();
        return GROUP_BENCHMARK.equals(group)
                || "voice_script".equals(normalizedRole)
                || "benchmark_json".equals(normalizedRole)
                || source.contains("DOUYIN")
                || fileName.contains("爆款对标")
                || fileName.contains("口播文案");
    }

    private boolean isStoryboardAsset(AssetEntity entity) {
        String group = entity.getAssetGroup() == null ? "" : entity.getAssetGroup().trim();
        String source = entity.getSourceType() == null ? "" : entity.getSourceType().trim().toUpperCase();
        String role = metadataText(entity.getMetadataJson(), "assetRole");
        String normalizedRole = role == null ? "" : role.trim().toLowerCase();
        String fileName = entity.getFileName() == null ? "" : entity.getFileName().toLowerCase();
        return GROUP_STORYBOARD.equals(group)
                || "storyboard_json".equals(normalizedRole)
                || source.equals("STORYBOARD_GENERATE")
                || source.equals("VIDEO_SCRIPT_ANALYZE")
                || source.equals("VIDEO_SCRIPT_URL_ANALYZE")
                || fileName.contains("分镜");
    }

    private String safeAssetType(AssetEntity entity) {
        return entity.getAssetType() == null ? "" : entity.getAssetType().trim().toUpperCase();
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
        if (viewerUserId.isPresent() && adminAccessService.isAdmin(viewerUserId.getAsLong())) {
            return;
        }
        Long owner = entity.getOwnerUserId();
        if (viewerUserId.isEmpty() || owner == null || !owner.equals(viewerUserId.getAsLong())) {
            throw new BusinessException(40400, "Asset does not exist");
        }
    }

    private AssetItem toItem(AssetEntity entity) {
        return toItem(entity, true);
    }

    private AssetItem toItem(AssetEntity entity, boolean includePreview) {
        String fileUrl = storedUrlResolver.resolveToPublicUrl(entity.getFileUrl());
        String thumb = resolvedThumbnailUrl(entity);
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
                entity.getAssetGroup(),
                includePreview ? enrichPreviewMetadata(entity) : entity.getMetadataJson(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String resolvedThumbnailUrl(AssetEntity entity) {
        if (entity == null) {
            return null;
        }
        JsonNode metadata = parseMetadataNode(entity.getMetadataJson());
        String raw = firstNonBlank(
                entity.getThumbnailUrl(),
                textAt(metadata, "thumbnailUrl"),
                textAt(metadata, "coverUrl"),
                textAt(metadata, "firstFrameUrl"),
                textAt(metadata, "posterUrl"),
                textAt(metadata, "/input/carImageUrls/0"),
                textAt(metadata, "/input/scenes/0/referenceImage"),
                textAt(metadata, "/input/scenes/0/imageUrls/0"),
                textAt(metadata, "/input/scene/referenceImage"),
                textAt(metadata, "/input/scene/imageUrls/0"),
                textAt(metadata, "/input/segmentRequest/imageUrl"),
                textAt(metadata, "/assetRoleBindings/0/url"),
                textAt(metadata, "/input/seedanceDiagnostics/assetRoleBindings/0/url"),
                textAt(metadata, "/segmentVideos/0/firstFrameUrl")
        );
        if (!StringUtils.hasText(raw) && isImageAsset(entity)) {
            raw = entity.getFileUrl();
        }
        return StringUtils.hasText(raw) ? storedUrlResolver.resolveToPublicUrl(raw) : null;
    }

    private String enrichPreviewMetadata(AssetEntity entity) {
        if (entity == null || !"JSON".equalsIgnoreCase(entity.getAssetType())) {
            return entity == null ? null : entity.getMetadataJson();
        }
        String metadataJson = entity.getMetadataJson();
        if (StringUtils.hasText(metadataText(metadataJson, "previewText"))
                || StringUtils.hasText(metadataText(metadataJson, "contentPreview"))) {
            return metadataJson;
        }
        if (entity.getTaskId() == null) {
            return metadataJson;
        }
        TaskEntity task = taskMapper.selectById(entity.getTaskId());
        if (task == null || !StringUtils.hasText(task.getOutputJson())) {
            return metadataJson;
        }
        String previewText = jsonAssetPreviewText(task.getOutputJson(), metadataJson, entity.getSourceType());
        if (!StringUtils.hasText(previewText)) {
            return metadataJson;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = StringUtils.hasText(metadataJson)
                    ? objectMapper.readValue(metadataJson, Map.class)
                    : new LinkedHashMap<>();
            if (meta == null) {
                meta = new LinkedHashMap<>();
            }
            meta.putIfAbsent("previewText", abbreviatePreview(previewText, 260));
            meta.putIfAbsent("contentPreview", abbreviatePreview(previewText, 260));
            meta.putIfAbsent("previewLabel", previewLabel(metadataJson, entity.getSourceType()));
            return objectMapper.writeValueAsString(meta);
        } catch (Exception ignored) {
            return metadataJson;
        }
    }

    private String jsonAssetPreviewText(String outputJson, String metadataJson, String sourceType) {
        try {
            JsonNode root = objectMapper.readTree(outputJson);
            String role = metadataText(metadataJson, "assetRole");
            String normalizedRole = role == null ? "" : role.trim().toLowerCase();
            String normalizedSource = sourceType == null ? "" : sourceType.trim().toUpperCase();
            if ("benchmark_json".equals(normalizedRole) || normalizedSource.contains("DOUYIN")) {
                String transcript = firstNonBlank(
                        textAt(root, "/transcriptResult/originalText"),
                        textAt(root, "/originalText"),
                        textAt(root, "/content"),
                        textAt(root, "/title")
                );
                if (StringUtils.hasText(transcript)) {
                    return transcript;
                }
                String title = firstNonBlank(textAt(root, "/parseResult/title"), textAt(root, "/title"));
                return StringUtils.hasText(title) ? "暂无口播转写；视频标题：" + title : null;
            }
            JsonNode shots = firstArray(root, "storyboard", "scripts", "shots", "scenes", "segments");
            if (shots != null && shots.size() > 0) {
                return storyboardPreviewText(shots);
            }
            return firstNonBlank(
                    textAt(root, "/summary"),
                    textAt(root, "/description"),
                    textAt(root, "/content"),
                    textAt(root, "/text")
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private String storyboardPreviewText(JsonNode shots) {
        List<String> pieces = new java.util.ArrayList<>();
        int limit = Math.min(3, shots.size());
        for (int i = 0; i < limit; i++) {
            JsonNode shot = shots.get(i);
            String order = firstNonBlank(
                    textAt(shot, "/order"),
                    textAt(shot, "/index"),
                    String.valueOf(i + 1)
            );
            String time = firstNonBlank(textAt(shot, "/time"), textAt(shot, "/duration"));
            String content = firstNonBlank(
                    textAt(shot, "/visual"),
                    textAt(shot, "/content"),
                    textAt(shot, "/narration"),
                    textAt(shot, "/highlight"),
                    textAt(shot, "/page")
            );
            if (StringUtils.hasText(content)) {
                pieces.add("镜头" + order + (StringUtils.hasText(time) ? " " + time : "") + "：" + content);
            }
        }
        return pieces.isEmpty() ? null : String.join("；", pieces);
    }

    private JsonNode firstArray(JsonNode root, String... fields) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isArray() && node.size() > 0) {
                return node;
            }
        }
        return null;
    }

    private String textAt(JsonNode root, String pointer) {
        if (root == null || !StringUtils.hasText(pointer)) {
            return null;
        }
        JsonNode node = pointer.startsWith("/") ? root.at(pointer) : root.path(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }

    private String previewLabel(String metadataJson, String sourceType) {
        String role = metadataText(metadataJson, "assetRole");
        if ("storyboard_json".equalsIgnoreCase(role)) {
            return "分镜预览";
        }
        if ("benchmark_json".equalsIgnoreCase(role) || (sourceType != null && sourceType.toUpperCase().contains("DOUYIN"))) {
            return "口播预览";
        }
        return "内容预览";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String abbreviatePreview(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    /**
     * @param normalizedScope {@code all}：未登录仅公共，已登录公共+本人；{@code global}：仅公共；{@code private}：仅本人（调用方已保证已登录）。
     */
    private void applyVisibilityScope(LambdaQueryWrapper<AssetEntity> w, OptionalLong viewerUserId, String normalizedScope) {
        switch (normalizedScope) {
            case "global":
                applyPublicVisibilityFilter(w);
                break;
            case "private":
                applyPrivateVisibilityFilter(w);
                if (!adminAccessService.isAdmin(viewerUserId.getAsLong())) {
                    w.eq(AssetEntity::getOwnerUserId, viewerUserId.getAsLong());
                }
                break;
            case "all":
            default:
                if (viewerUserId.isEmpty()) {
                    applyPublicVisibilityFilter(w);
                } else {
                    long uid = viewerUserId.getAsLong();
                    w.and(q -> q.eq(AssetEntity::getVisibility, VISIBILITY_PUBLIC)
                            .or(n -> n.isNull(AssetEntity::getVisibility).isNull(AssetEntity::getOwnerUserId))
                            .or()
                            .eq(AssetEntity::getVisibility, VISIBILITY_PRIVATE).eq(AssetEntity::getOwnerUserId, uid));
                }
        }
    }

    private void applyPublicVisibilityFilter(LambdaQueryWrapper<AssetEntity> w) {
        w.and(q -> q.eq(AssetEntity::getVisibility, VISIBILITY_PUBLIC)
                .or(n -> n.isNull(AssetEntity::getVisibility).isNull(AssetEntity::getOwnerUserId)));
    }

    private void applyPrivateVisibilityFilter(LambdaQueryWrapper<AssetEntity> w) {
        w.and(q -> q.eq(AssetEntity::getVisibility, VISIBILITY_PRIVATE)
                .or(n -> n.isNull(AssetEntity::getVisibility).isNotNull(AssetEntity::getOwnerUserId)));
    }

    private void applyViewerFirstSort(LambdaQueryWrapper<AssetEntity> w, OptionalLong viewerUserId, String normalizedScope) {
        if (w == null || viewerUserId.isEmpty() || !"all".equals(normalizedScope)) {
            return;
        }
        // PRIVATE sorts before PUBLIC, so the current user's own assets are not buried behind the public pool.
        w.orderByAsc(AssetEntity::getVisibility);
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

    private String normalizeAssetGroupFilter(String assetGroup) {
        if (!StringUtils.hasText(assetGroup)) {
            return null;
        }
        String trimmed = assetGroup.trim();
        if (GROUP_UNGROUPED_FILTER.equals(trimmed)) {
            return GROUP_UNGROUPED_FILTER;
        }
        return normalizeAssetGroupRequired(trimmed);
    }

    private String normalizeBusinessDomain(String businessDomain) {
        if (!StringUtils.hasText(businessDomain)) {
            return null;
        }
        String normalized = businessDomain.trim().toLowerCase(Locale.ROOT);
        return ("pet".equals(normalized) || "pet_creation".equals(normalized)) ? "pet" : null;
    }

    private void applyBusinessDomainFilter(LambdaQueryWrapper<AssetEntity> w, String businessDomain) {
        if (!"pet".equals(businessDomain)) {
            return;
        }
        w.and(q -> q.like(AssetEntity::getMetadataJson, "businessDomain")
                .like(AssetEntity::getMetadataJson, "pet")
                .or()
                .like(AssetEntity::getMetadataJson, "pet_creation"));
    }

    private String normalizeAssetGroupRequired(String assetGroup) {
        if (!StringUtils.hasText(assetGroup)) {
            return null;
        }
        String trimmed = assetGroup.trim();
        if (trimmed.length() > 60) {
            throw new BusinessException(40000, "资产分组不能超过60个字符");
        }
        if (GROUP_LEGACY_SCRIPT.equals(trimmed) || GROUP_LEGACY_COPY_ASSET.equals(trimmed)) {
            return GROUP_BENCHMARK;
        }
        if (GROUP_LEGACY_STORYBOARD_ASSET.equals(trimmed)) {
            return GROUP_STORYBOARD;
        }
        return trimmed;
    }

    private String normalizeAssetGroupForStorage(String assetGroup) {
        if (!StringUtils.hasText(assetGroup)) {
            return null;
        }
        String trimmed = assetGroup.trim();
        if (GROUP_LEGACY_SCRIPT.equals(trimmed) || GROUP_LEGACY_COPY_ASSET.equals(trimmed)) {
            return GROUP_BENCHMARK;
        }
        if (GROUP_LEGACY_STORYBOARD_ASSET.equals(trimmed)) {
            return GROUP_STORYBOARD;
        }
        return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
    }

    private String initialThumbnailUrl(String assetType, String fileUrl, String metadataJson) {
        String fromMetadata = firstNonBlank(
                metadataText(metadataJson, "thumbnailUrl"),
                metadataText(metadataJson, "coverUrl"),
                metadataText(metadataJson, "firstFrameUrl"),
                metadataText(metadataJson, "posterUrl")
        );
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        String normalizedType = assetType == null ? "" : assetType.trim().toUpperCase();
        return ("IMAGE".equals(normalizedType) || "COVER".equals(normalizedType)) ? fileUrl : null;
    }

    private String firstBundleImageUrl(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode images = root.path("images");
        if (!images.isArray()) {
            return null;
        }
        for (JsonNode image : images) {
            String url = firstNonBlank(
                    textAt(image, "/url"),
                    textAt(image, "/fileUrl"),
                    textAt(image, "/thumbnailUrl"),
                    textAt(image, "/coverUrl")
            );
            if (StringUtils.hasText(url)) {
                return url;
            }
        }
        return null;
    }

    private int countBundleImages(JsonNode root) {
        if (root == null) {
            return 0;
        }
        JsonNode images = root.path("images");
        return images.isArray() ? images.size() : 0;
    }

    private boolean isCarModelBundleAsset(AssetEntity entity) {
        if (entity == null || !"JSON".equalsIgnoreCase(entity.getAssetType())) {
            return false;
        }
        String metadataJson = entity.getMetadataJson();
        String assetRole = metadataText(metadataJson, "assetRole");
        String bundleType = metadataText(metadataJson, "bundleType");
        String from = metadataText(metadataJson, "from");
        return "car_model_bundle".equalsIgnoreCase(assetRole)
                || "car_model".equalsIgnoreCase(bundleType)
                || "car_model_bundle".equalsIgnoreCase(from)
                || GROUP_CAR_MODEL_BUNDLE.equals(entity.getAssetGroup());
    }

    private boolean shouldHidePublicCarModelBundleComponent(AssetEntity entity) {
        return entity != null
                && VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(entity))
                && isCarModelBundleComponentImage(entity);
    }

    private boolean isCarModelBundleComponentImage(AssetEntity entity) {
        if (!isImageAsset(entity)) {
            return false;
        }
        String metadataJson = entity.getMetadataJson();
        String assetRole = metadataText(metadataJson, "assetRole");
        String from = metadataText(metadataJson, "from");
        String normalizedRole = assetRole == null ? "" : assetRole.trim().toLowerCase();
        return GROUP_CAR_MODEL_BUNDLE.equals(entity.getAssetGroup())
                || "car_model_bundle_image".equalsIgnoreCase(from)
                || normalizedRole.startsWith("car_")
                || metadataBoolean(metadataJson, "hiddenInPublicAssetCenter")
                || metadataBoolean(metadataJson, "carModelBundleComponent");
    }

    private boolean isImageAsset(AssetEntity entity) {
        if (entity == null) {
            return false;
        }
        String assetType = entity.getAssetType() == null ? "" : entity.getAssetType().trim().toUpperCase();
        String mimeType = entity.getMimeType() == null ? "" : entity.getMimeType().trim().toLowerCase();
        return "IMAGE".equals(assetType) || "COVER".equals(assetType) || mimeType.startsWith("image/");
    }

    private void markCarModelBundleComponentAssets(JsonNode root, long uid) {
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode images = root.path("images");
        if (!images.isArray()) {
            return;
        }
        boolean admin = adminAccessService.isAdmin(uid);
        for (JsonNode image : images) {
            JsonNode assetIdNode = image.path("assetId");
            if (!assetIdNode.canConvertToLong()) {
                continue;
            }
            long assetId = assetIdNode.asLong();
            if (assetId <= 0) {
                continue;
            }
            AssetEntity component = assetMapper.selectById(assetId);
            if (component == null || !isImageAsset(component)) {
                continue;
            }
            if (!canMarkCarModelBundleComponent(component, uid, admin)) {
                continue;
            }

            String metadata = StringUtils.hasText(component.getMetadataJson()) ? component.getMetadataJson() : "{}";
            String role = textAt(image, "/role");
            if (StringUtils.hasText(role)) {
                metadata = appendMetadata(metadata, "assetRole", role.trim());
            }
            metadata = appendMetadata(metadata, "assetGroup", GROUP_CAR_MODEL_BUNDLE);
            metadata = appendMetadata(metadata, "hiddenInPublicAssetCenter", true);
            metadata = appendMetadata(metadata, "carModelBundleComponent", true);

            LambdaUpdateWrapper<AssetEntity> update = new LambdaUpdateWrapper<>();
            update.eq(AssetEntity::getAssetId, assetId)
                    .set(AssetEntity::getAssetGroup, GROUP_CAR_MODEL_BUNDLE)
                    .set(AssetEntity::getMetadataJson, metadata)
                    .set(AssetEntity::getUpdatedAt, LocalDateTime.now());
            if (VISIBILITY_PUBLIC.equalsIgnoreCase(safeVisibility(component))) {
                update.set(AssetEntity::getVisibility, VISIBILITY_PRIVATE)
                        .set(AssetEntity::getPublishedAt, null);
                if (component.getOwnerUserId() == null) {
                    update.set(AssetEntity::getOwnerUserId, uid);
                }
                if (component.getCreatedByUserId() == null) {
                    update.set(AssetEntity::getCreatedByUserId, uid);
                }
            }
            assetMapper.update(null, update);
        }
    }

    private boolean canMarkCarModelBundleComponent(AssetEntity entity, long uid, boolean admin) {
        if (admin) {
            return true;
        }
        Long owner = entity.getOwnerUserId();
        Long createdBy = entity.getCreatedByUserId();
        return owner != null && owner == uid || createdBy != null && createdBy == uid;
    }

    private void assertCarModelBundleWritable(AssetEntity entity, long uid) {
        String visibility = safeVisibility(entity);
        if (VISIBILITY_PUBLIC.equalsIgnoreCase(visibility)) {
            Long createdBy = entity.getCreatedByUserId();
            Long owner = entity.getOwnerUserId();
            if (adminAccessService.isAdmin(uid)
                    || (createdBy != null && createdBy.equals(uid))
                    || (owner != null && owner.equals(uid))) {
                return;
            }
            throw new BusinessException(40300, "无权编辑该公共车型素材包");
        }
        Long owner = entity.getOwnerUserId();
        if (owner == null || !owner.equals(uid)) {
            throw new BusinessException(40300, "无权编辑该车型素材包");
        }
    }

    private JsonNode parseJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new BusinessException(40000, "JSON 内容必须是合法 JSON");
        }
    }

    private boolean isCarModelBundlePayload(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        String bundleType = textAt(root, "/bundleType");
        String assetRole = textAt(root, "/assetRole");
        return "car_model".equalsIgnoreCase(bundleType)
                || "car_model_bundle".equalsIgnoreCase(assetRole);
    }

    private String normalizeJsonDisplayFileName(String fileName, String fallback, Long assetId) {
        String name = firstNonBlank(fileName, fallback, "car-model-bundle-" + assetId + ".json");
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (!StringUtils.hasText(name)) {
            name = "car-model-bundle-" + assetId + ".json";
        }
        if (!name.toLowerCase().endsWith(".json")) {
            name = name + ".json";
        }
        return name.length() <= 160 ? name : name.substring(0, 155) + ".json";
    }

    private String normalizeEditableTextFileName(String fileName, String fallback, Long assetId, String assetType) {
        String extension = "JSON".equals(assetType) ? ".json" : ".txt";
        String name = StringUtils.hasText(fileName)
                ? fileName.trim()
                : StringUtils.hasText(fallback) ? fallback.trim() : "asset-content-" + assetId + extension;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (!StringUtils.hasText(name)) {
            name = "asset-content-" + assetId + extension;
        }
        if (!name.toLowerCase().endsWith(extension)) {
            name = name + extension;
        }
        return name.length() <= 160 ? name : name.substring(0, 160 - extension.length()) + extension;
    }

    private String inferAssetGroup(String metadataJson, String assetType, String sourceType) {
        String declared = metadataText(metadataJson, "assetGroup");
        if (StringUtils.hasText(declared)) {
            return normalizeAssetGroupForStorage(declared);
        }
        String assetRole = metadataText(metadataJson, "assetRole");
        String bundleType = metadataText(metadataJson, "bundleType");
        String from = metadataText(metadataJson, "from");
        String normalizedRole = assetRole == null ? "" : assetRole.trim().toLowerCase();
        String normalizedBundleType = bundleType == null ? "" : bundleType.trim().toLowerCase();
        String normalizedFrom = from == null ? "" : from.trim().toLowerCase();
        if ("car_model_bundle".equals(normalizedRole)
                || normalizedRole.startsWith("car_")
                || "car_model".equals(normalizedBundleType)
                || "car_model_bundle".equals(normalizedFrom)
                || "car_model_bundle_image".equals(normalizedFrom)) {
            return GROUP_CAR_MODEL_BUNDLE;
        }
        String normalizedSource = sourceType == null ? "" : sourceType.trim().toUpperCase();
        if ("benchmark_json".equals(normalizedRole)
                || "voice_script".equals(normalizedRole)
                || normalizedSource.contains("DOUYIN")
                || "douyin_benchmark".equals(normalizedFrom)
                || "car_sales_benchmark_upload".equals(normalizedFrom)) {
            return GROUP_BENCHMARK;
        }
        if ("storyboard_json".equals(normalizedRole)
                || normalizedSource.equals("STORYBOARD_GENERATE")
                || normalizedSource.equals("VIDEO_SCRIPT_ANALYZE")
                || normalizedSource.equals("VIDEO_SCRIPT_URL_ANALYZE")) {
            return GROUP_STORYBOARD;
        }
        return null;
    }

    private String metadataText(String metadataJson, String key) {
        if (!StringUtils.hasText(metadataJson) || !StringUtils.hasText(key)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(metadataJson, Map.class);
            Object raw = parsed == null ? null : parsed.get(key);
            return raw instanceof String text ? text : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode parseMetadataNode(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(metadataJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String metadataUrlAt(String metadataJson, String pointer) {
        if (!StringUtils.hasText(metadataJson) || !StringUtils.hasText(pointer)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson).at(pointer);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            if (node.isTextual()) {
                String text = node.asText();
                return StringUtils.hasText(text) ? text.trim() : null;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean metadataBoolean(String metadataJson, String key) {
        if (!StringUtils.hasText(metadataJson) || !StringUtils.hasText(key)) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(metadataJson, Map.class);
            Object raw = parsed == null ? null : parsed.get(key);
            if (raw instanceof Boolean value) {
                return value;
            }
            if (raw instanceof String text) {
                return Boolean.parseBoolean(text.trim());
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String mergeMetadataJson(String existingJson, String incomingJson) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (StringUtils.hasText(existingJson)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(existingJson, Map.class);
                if (parsed != null) {
                    meta.putAll(parsed);
                }
            } catch (Exception ignored) {
                // Keep cover updates available even when legacy metadata is malformed.
            }
        }
        if (StringUtils.hasText(incomingJson)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> incoming = objectMapper.readValue(incomingJson, Map.class);
                if (incoming != null) {
                    meta.putAll(incoming);
                }
            } catch (Exception ex) {
                throw new BusinessException(40000, "metadataJson 必须是合法 JSON 对象");
            }
        }
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void applyKeywordFilter(LambdaQueryWrapper<AssetEntity> wrapper, String normalizedKeyword) {
        if (wrapper == null || normalizedKeyword == null) {
            return;
        }
        String pattern = "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%";
        wrapper.and(q -> q
                .apply("lower(coalesce(file_name, '')) like {0}", pattern)
                .or().apply("lower(coalesce(asset_group, '')) like {0}", pattern)
                .or().apply("lower(coalesce(source_type, '')) like {0}", pattern)
                .or().apply("lower(coalesce(asset_type, '')) like {0}", pattern)
                .or().apply("lower(coalesce(kind, '')) like {0}", pattern)
                .or().apply("lower(coalesce(metadata_json, '')) like {0}", pattern));
    }

    private void excludePublicCarModelBundleComponentImages(LambdaQueryWrapper<AssetEntity> wrapper) {
        if (wrapper == null) {
            return;
        }
        wrapper.apply("not ("
                + "(upper(coalesce(visibility, '')) = {0} or (visibility is null and owner_user_id is null))"
                + " and (upper(coalesce(asset_type, '')) in ('IMAGE','COVER') or lower(coalesce(mime_type, '')) like 'image/%')"
                + " and (asset_group = {1}"
                + " or lower(coalesce(metadata_json, '')) like '%\"from\":\"car_model_bundle_image\"%'"
                + " or lower(coalesce(metadata_json, '')) like '%\"assetrole\":\"car%'"
                + " or lower(coalesce(metadata_json, '')) like '%\"hiddeninpublicassetcenter\":true%'"
                + " or lower(coalesce(metadata_json, '')) like '%\"carmodelbundlecomponent\":true%'))",
                VISIBILITY_PUBLIC, GROUP_CAR_MODEL_BUNDLE);
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

    private void applyPagination(LambdaQueryWrapper<AssetEntity> w, Integer pageNo, Integer pageSize) {
        if (w == null || pageSize == null || pageSize <= 0) {
            return;
        }
        int size = Math.min(Math.max(pageSize, 1), 100);
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        long offset = (long) (page - 1) * size;
        w.last("LIMIT " + offset + "," + size);
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

    private String ensureJsonFileName(String fileName, Long taskId) {
        String name = StringUtils.hasText(fileName)
                ? fileName.trim()
                : "generated-task-" + (taskId == null ? "unknown" : taskId) + ".json";
        if (!name.toLowerCase().endsWith(".json")) {
            name = name + ".json";
        }
        return name;
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
