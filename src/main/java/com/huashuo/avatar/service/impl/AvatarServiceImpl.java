package com.huashuo.avatar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.avatar.dto.AvatarGenerateRequest;
import com.huashuo.avatar.dto.AvatarGenerateResponse;
import com.huashuo.avatar.dto.AvatarTaskDetailResponse;
import com.huashuo.avatar.dto.AvatarUpdateRequest;
import com.huashuo.avatar.entity.AvatarProfileEntity;
import com.huashuo.avatar.job.AvatarGenerateTaskExecutor;
import com.huashuo.avatar.mapper.AvatarProfileMapper;
import com.huashuo.avatar.service.AvatarService;
import com.huashuo.avatar.vo.AvatarItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.storage.resolve.StoredUrlResolver;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.UploadPublicBaseProvider;
import com.huashuo.upload.tos.VolcengineTosProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.Map;

@Service
public class AvatarServiceImpl implements AvatarService {

    private final AvatarProfileMapper avatarProfileMapper;
    private final TaskService taskService;
    private final AssetService assetService;
    private final AvatarGenerateTaskExecutor avatarGenerateTaskExecutor;
    private final UploadPublicBaseProvider uploadPublicBaseProvider;
    private final TosUploadService tosUploadService;
    private final VolcengineTosProperties volcengineTosProperties;
    private final StorageService storageService;
    private final StoredUrlResolver storedUrlResolver;
    private final ObjectMapper objectMapper;

    public AvatarServiceImpl(
            AvatarProfileMapper avatarProfileMapper,
            TaskService taskService,
            AssetService assetService,
            AvatarGenerateTaskExecutor avatarGenerateTaskExecutor,
            UploadPublicBaseProvider uploadPublicBaseProvider,
            TosUploadService tosUploadService,
            VolcengineTosProperties volcengineTosProperties,
            StorageService storageService,
            StoredUrlResolver storedUrlResolver,
            ObjectMapper objectMapper
    ) {
        this.avatarProfileMapper = avatarProfileMapper;
        this.taskService = taskService;
        this.assetService = assetService;
        this.avatarGenerateTaskExecutor = avatarGenerateTaskExecutor;
        this.uploadPublicBaseProvider = uploadPublicBaseProvider;
        this.tosUploadService = tosUploadService;
        this.volcengineTosProperties = volcengineTosProperties;
        this.storageService = storageService;
        this.storedUrlResolver = storedUrlResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AvatarItem upload(Long projectId, String avatarName, MultipartFile file, Long ownerUserId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40000, "Avatar image is required");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        if (!contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(40000, "Avatar upload only supports image files");
        }
        String safeName = StringUtils.hasText(avatarName) ? avatarName.trim() : "上传形象";
        String originalFileName = file.getOriginalFilename() == null ? "avatar.png" : file.getOriginalFilename();

        UploadResult stored = storageService.upload(file, "avatar");
        AssetItem asset = assetService.createAvatarImageAsset(
                ownerUserId,
                projectId,
                null,
                originalFileName,
                stored.objectKey(),
                stored.url(),
                contentType,
                stored.size(),
                "USER_UPLOAD",
                "{\"from\":\"avatar_upload\"}"
        );

        AvatarProfileEntity entity = new AvatarProfileEntity();
        entity.setProjectId(projectId);
        entity.setTaskId(null);
        entity.setAssetId(asset.assetId());
        entity.setAvatarName(safeName);
        entity.setSourceType("USER_UPLOAD");
        entity.setPrompt(null);
        entity.setReferenceAssetIds(null);
        entity.setPreviewUrl(asset.fileUrl());
        entity.setMetadataJson("{\"from\":\"avatar_upload\"}");
        entity.setDefaultAvatar(hasDefaultAvatar() ? 0 : 1);
        avatarProfileMapper.insert(entity);
        return requireAvatar(entity.getAvatarId());
    }

    @Override
    public AvatarGenerateResponse generate(AvatarGenerateRequest request, String traceId, Long requestingUserId,
                                           String idempotencyKey) {
        int imageCount = request.imageCount() == null ? 4 : Math.max(1, Math.min(request.imageCount(), 4));
        List<Long> referenceAssetIds = request.referenceAssetIds() == null ? List.of() : request.referenceAssetIds();
        List<String> referenceImageUrls = resolveReferenceImageUrls(referenceAssetIds, requestingUserId);

        Map<String, Object> input = new LinkedHashMap<>();
        if (request.projectId() != null) {
            input.put("projectId", request.projectId());
        }
        input.put("avatarName", request.avatarName().trim());
        input.put("prompt", request.prompt().trim());
        input.put("referenceAssetIds", referenceAssetIds);
        input.put("referenceImageUrls", referenceImageUrls);
        input.put("style", StringUtils.hasText(request.style()) ? request.style().trim() : "REALISTIC");
        input.put("imageCount", imageCount);
        input.put("size", StringUtils.hasText(request.size()) ? request.size().trim() : "2K");
        if (requestingUserId != null) {
            input.put("requestingUserId", requestingUserId);
        }

        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.AVATAR_GENERATE,
                toJson(input),
                traceId,
                requestingUserId,
                null,
                null,
                idempotencyKey
        );
        avatarGenerateTaskExecutor.run(task.taskId());
        return new AvatarGenerateResponse(task.taskId(), request.projectId(), TaskTypeCode.AVATAR_GENERATE, task.status());
    }

    @Override
    public AvatarTaskDetailResponse getGenerateTask(Long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (!TaskTypeCode.AVATAR_GENERATE.equals(task.taskType())) {
            throw new BusinessException(40400, "Not an avatar generation task");
        }
        List<AssetItem> assets = new ArrayList<>();
        List<AvatarItem> avatars = new ArrayList<>();
        if ("SUCCESS".equals(task.status()) && StringUtils.hasText(task.outputJson())) {
            try {
                JsonNode output = objectMapper.readTree(task.outputJson());
                output.path("assetIds").forEach(node -> assets.add(assetService.getAsset(node.asLong())));
                output.path("avatarIds").forEach(node -> avatars.add(requireAvatar(node.asLong())));
            } catch (JsonProcessingException ignored) {
                // Task detail can still return status even if legacy output is malformed.
            }
        }
        return new AvatarTaskDetailResponse(
                task.taskId(),
                task.projectId(),
                task.taskType(),
                task.status(),
                task.progress() != null ? task.progress() : progressOf(task.status()),
                task.errorMessage(),
                assets,
                avatars
        );
    }

    @Override
    public List<AvatarItem> listProjectAvatars(Long projectId) {
        LambdaQueryWrapper<AvatarProfileEntity> w = new LambdaQueryWrapper<>();
        if (projectId != null) {
            w.eq(AvatarProfileEntity::getProjectId, projectId);
        }
        w.orderByDesc(AvatarProfileEntity::getDefaultAvatar)
                .orderByDesc(AvatarProfileEntity::getCreatedAt, AvatarProfileEntity::getAvatarId);
        return avatarProfileMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
    public AvatarItem getAvatar(Long avatarId) {
        return requireAvatar(avatarId);
    }

    @Override
    @Transactional
    public AvatarItem updateAvatar(Long avatarId, AvatarUpdateRequest request) {
        AvatarProfileEntity existing = avatarProfileMapper.selectById(avatarId);
        if (existing == null) {
            throw new BusinessException(40400, "Avatar does not exist");
        }
        if (Boolean.TRUE.equals(request.defaultAvatar())) {
            LambdaUpdateWrapper<AvatarProfileEntity> clear = new LambdaUpdateWrapper<>();
            if (existing.getProjectId() == null) {
                clear.isNull(AvatarProfileEntity::getProjectId);
            } else {
                clear.eq(AvatarProfileEntity::getProjectId, existing.getProjectId());
            }
            clear.set(AvatarProfileEntity::getDefaultAvatar, 0)
                    .set(AvatarProfileEntity::getUpdatedAt, LocalDateTime.now());
            avatarProfileMapper.update(null, clear);
        }

        LambdaUpdateWrapper<AvatarProfileEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AvatarProfileEntity::getAvatarId, avatarId)
                .set(AvatarProfileEntity::getUpdatedAt, LocalDateTime.now());
        if (StringUtils.hasText(request.avatarName())) {
            update.set(AvatarProfileEntity::getAvatarName, request.avatarName().trim());
        }
        if (request.defaultAvatar() != null) {
            update.set(AvatarProfileEntity::getDefaultAvatar, Boolean.TRUE.equals(request.defaultAvatar()) ? 1 : 0);
        }
        avatarProfileMapper.update(null, update);
        return requireAvatar(avatarId);
    }

    private List<String> resolveReferenceImageUrls(List<Long> referenceAssetIds, Long requestingUserId) {
        OptionalLong viewer = requestingUserId == null ? OptionalLong.empty() : OptionalLong.of(requestingUserId);
        List<String> urls = new ArrayList<>();
        for (Long assetId : referenceAssetIds) {
            if (assetId == null) {
                continue;
            }
            AssetItem asset = assetService.getAssetForViewer(assetId, viewer);
            if (!"IMAGE".equals(asset.assetType()) && !"COVER".equals(asset.assetType())) {
                throw new BusinessException(40000, "Reference asset must be an image");
            }
            ensureReferenceImageOnObjectStorage(asset);
            urls.add(toPublicReferenceUrl(asset.fileUrl()));
        }
        return urls;
    }

    /**
     * 豆包只认公网可下载 URL。启用 TOS 时公网地址指向 Bucket，若资产仅在本机磁盘而未 putObject，会 404。
     * 在提交图生图前将参考图按 {@code fileUrl} 对应 key 同步到 TOS（与「上传形象」逻辑一致）。
     */
    private void ensureReferenceImageOnObjectStorage(AssetItem asset) {
        if (!volcengineTosProperties.enabled()) {
            return;
        }
        String relative = asset.fileUrl();
        if (!StringUtils.hasText(relative)) {
            return;
        }
        String trimmed = relative.trim();
        if (isPublicHttpUrl(trimmed)) {
            return;
        }
        if (!trimmed.startsWith("/")) {
            return;
        }
        if (!StringUtils.hasText(asset.filePath())) {
            throw new BusinessException(40000, "参考图缺少服务器本地路径，无法同步到对象存储");
        }
        Path path = null;
        try {
            path = Path.of(asset.filePath());
        } catch (Exception e) {
            throw new BusinessException(40000, "参考图路径无效: " + asset.fileName());
        }
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(
                    40000,
                    "参考图在服务器上不存在（可能未同步到对象存储）。请重新上传该图或换一张参考图: " + asset.fileName()
            );
        }
        String objectKey = TosUploadService.previewUrlToObjectKey(trimmed);
        String contentType = StringUtils.hasText(asset.mimeType()) ? asset.mimeType() : "image/png";
        try (var in = Files.newInputStream(path)) {
            tosUploadService.putPublicObject(objectKey, in, Files.size(path), contentType);
        } catch (IOException e) {
            throw new BusinessException(50000, "参考图同步到对象存储失败: " + e.getMessage());
        }
    }

    private String toPublicReferenceUrl(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            throw new BusinessException(40000, "Reference image URL is empty");
        }
        String trimmed = fileUrl.trim();
        if (isPublicHttpUrl(trimmed)) {
            return trimmed;
        }
        if (trimmed.startsWith("/") && StringUtils.hasText(uploadPublicBaseProvider.effectivePublicBaseUrl())) {
            String resolved = uploadPublicBaseProvider.effectivePublicBaseUrl() + trimmed;
            if (isPublicHttpUrl(resolved)) {
                return resolved;
            }
        }
        throw new BusinessException(
                40000,
                "参考图需能拼成豆包可访问的公网 URL；当前为本地相对路径且未配置公网基址。"
                        + "请设置 HUASHUO_UPLOAD_PUBLIC_BASE_URL，或配置 volcengine.tos.public-base-url；"
                        + "使用 TOS 上传时需 VOLCENGINE_TOS_ENABLED=true、密钥，并在启用后重新上传参考图。"
                        + "也可不选参考图文生图。"
        );
    }

    private boolean isPublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            if (!StringUtils.hasText(host)) {
                return false;
            }
            String normalizedHost = host.toLowerCase();
            if ("localhost".equals(normalizedHost) || normalizedHost.startsWith("127.")
                    || normalizedHost.startsWith("10.") || normalizedHost.startsWith("192.168.")) {
                return false;
            }
            if (normalizedHost.startsWith("172.")) {
                String[] parts = normalizedHost.split("\\.");
                if (parts.length > 1) {
                    int second = Integer.parseInt(parts[1]);
                    return second < 16 || second > 31;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private AvatarItem requireAvatar(Long avatarId) {
        AvatarProfileEntity entity = avatarProfileMapper.selectById(avatarId);
        if (entity == null) {
            throw new BusinessException(40400, "Avatar does not exist");
        }
        return toItem(entity);
    }

    private boolean hasDefaultAvatar() {
        LambdaQueryWrapper<AvatarProfileEntity> w = new LambdaQueryWrapper<>();
        w.isNull(AvatarProfileEntity::getProjectId)
                .eq(AvatarProfileEntity::getDefaultAvatar, 1)
                .last("limit 1");
        return avatarProfileMapper.selectOne(w) != null;
    }

    private AvatarItem toItem(AvatarProfileEntity entity) {
        return new AvatarItem(
                entity.getAvatarId(),
                entity.getProjectId(),
                entity.getTaskId(),
                entity.getAssetId(),
                entity.getAvatarName(),
                entity.getSourceType(),
                entity.getPrompt(),
                entity.getReferenceAssetIds(),
                storedUrlResolver.resolveToPublicUrl(entity.getPreviewUrl()),
                entity.getMetadataJson(),
                entity.getDefaultAvatar() != null && entity.getDefaultAvatar() == 1,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private Integer progressOf(String status) {
        return switch (status) {
            case "QUEUED" -> 10;
            case "RUNNING" -> 60;
            case "SUCCESS" -> 100;
            case "FAILED", "RETRYABLE", "CANCELED" -> 0;
            default -> null;
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "Failed to serialize JSON");
        }
    }
}
