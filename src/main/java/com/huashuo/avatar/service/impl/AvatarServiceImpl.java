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
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.UploadPublicBaseProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.Map;
import java.util.UUID;

@Service
public class AvatarServiceImpl implements AvatarService {

    private final AvatarProfileMapper avatarProfileMapper;
    private final TaskService taskService;
    private final AssetService assetService;
    private final AvatarGenerateTaskExecutor avatarGenerateTaskExecutor;
    private final UploadProperties uploadProperties;
    private final UploadPublicBaseProvider uploadPublicBaseProvider;
    private final TosUploadService tosUploadService;
    private final ObjectMapper objectMapper;

    public AvatarServiceImpl(
            AvatarProfileMapper avatarProfileMapper,
            TaskService taskService,
            AssetService assetService,
            AvatarGenerateTaskExecutor avatarGenerateTaskExecutor,
            UploadProperties uploadProperties,
            UploadPublicBaseProvider uploadPublicBaseProvider,
            TosUploadService tosUploadService,
            ObjectMapper objectMapper
    ) {
        this.avatarProfileMapper = avatarProfileMapper;
        this.taskService = taskService;
        this.assetService = assetService;
        this.avatarGenerateTaskExecutor = avatarGenerateTaskExecutor;
        this.uploadProperties = uploadProperties;
        this.uploadPublicBaseProvider = uploadPublicBaseProvider;
        this.tosUploadService = tosUploadService;
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
        String suffix = suffixOf(originalFileName, ".png");
        String datePath = LocalDate.now().toString();
        String storedFileName = "avatar-upload-" + UUID.randomUUID() + suffix;
        Path targetDir = Path.of(uploadProperties.localRoot(), "avatar", datePath);
        Path targetFile = targetDir.resolve(storedFileName);
        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetFile);
        } catch (IOException e) {
            throw new BusinessException(50000, "Avatar upload failed: " + e.getMessage());
        }

        String previewUrl = uploadProperties.previewPrefix() + "/avatar/" + datePath + "/" + storedFileName;
        try (var in = Files.newInputStream(targetFile)) {
            tosUploadService.putPublicObject(
                    TosUploadService.previewUrlToObjectKey(previewUrl),
                    in,
                    Files.size(targetFile),
                    contentType
            );
        } catch (IOException e) {
            throw new BusinessException(50000, "Avatar file read failed after upload: " + e.getMessage());
        }
        AssetItem asset = assetService.createAvatarImageAsset(
                ownerUserId,
                projectId,
                null,
                originalFileName,
                targetFile.toAbsolutePath().toString(),
                previewUrl,
                contentType,
                file.getSize(),
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
    public AvatarGenerateResponse generate(AvatarGenerateRequest request, String traceId, Long requestingUserId) {
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
                requestingUserId
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
            urls.add(toPublicReferenceUrl(asset.fileUrl()));
        }
        return urls;
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
                entity.getPreviewUrl(),
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

    private String suffixOf(String fileName, String fallback) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return fallback;
        }
        return fileName.substring(dotIndex);
    }
}
