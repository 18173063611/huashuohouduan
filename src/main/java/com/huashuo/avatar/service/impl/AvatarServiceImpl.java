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
import com.huashuo.project.service.ProjectService;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.upload.config.UploadProperties;
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
import java.util.Map;
import java.util.UUID;

@Service
public class AvatarServiceImpl implements AvatarService {

    private final AvatarProfileMapper avatarProfileMapper;
    private final ProjectService projectService;
    private final TaskService taskService;
    private final AssetService assetService;
    private final AvatarGenerateTaskExecutor avatarGenerateTaskExecutor;
    private final UploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    public AvatarServiceImpl(
            AvatarProfileMapper avatarProfileMapper,
            ProjectService projectService,
            TaskService taskService,
            AssetService assetService,
            AvatarGenerateTaskExecutor avatarGenerateTaskExecutor,
            UploadProperties uploadProperties,
            ObjectMapper objectMapper
    ) {
        this.avatarProfileMapper = avatarProfileMapper;
        this.projectService = projectService;
        this.taskService = taskService;
        this.assetService = assetService;
        this.avatarGenerateTaskExecutor = avatarGenerateTaskExecutor;
        this.uploadProperties = uploadProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AvatarItem upload(Long projectId, String avatarName, MultipartFile file) {
        projectService.getProject(projectId);
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
        AssetItem asset = assetService.createAvatarImageAsset(
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
        entity.setDefaultAvatar(hasDefaultAvatar(projectId) ? 0 : 1);
        avatarProfileMapper.insert(entity);
        return requireAvatar(entity.getAvatarId());
    }

    @Override
    public AvatarGenerateResponse generate(AvatarGenerateRequest request, String traceId) {
        projectService.getProject(request.projectId());
        int imageCount = request.imageCount() == null ? 4 : Math.max(1, Math.min(request.imageCount(), 4));
        List<Long> referenceAssetIds = request.referenceAssetIds() == null ? List.of() : request.referenceAssetIds();
        List<String> referenceImageUrls = resolveReferenceImageUrls(referenceAssetIds);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", request.projectId());
        input.put("avatarName", request.avatarName().trim());
        input.put("prompt", request.prompt().trim());
        input.put("referenceAssetIds", referenceAssetIds);
        input.put("referenceImageUrls", referenceImageUrls);
        input.put("style", StringUtils.hasText(request.style()) ? request.style().trim() : "REALISTIC");
        input.put("imageCount", imageCount);
        input.put("size", StringUtils.hasText(request.size()) ? request.size().trim() : "2K");

        TaskItem task = taskService.createTask(
                request.projectId(),
                TaskTypeCode.AVATAR_GENERATE,
                toJson(input),
                traceId
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
                progressOf(task.status()),
                task.errorMessage(),
                assets,
                avatars
        );
    }

    @Override
    public List<AvatarItem> listProjectAvatars(Long projectId) {
        projectService.getProject(projectId);
        LambdaQueryWrapper<AvatarProfileEntity> w = new LambdaQueryWrapper<>();
        w.eq(AvatarProfileEntity::getProjectId, projectId)
                .orderByDesc(AvatarProfileEntity::getDefaultAvatar)
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
            clear.eq(AvatarProfileEntity::getProjectId, existing.getProjectId())
                    .set(AvatarProfileEntity::getDefaultAvatar, 0)
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

    private List<String> resolveReferenceImageUrls(List<Long> referenceAssetIds) {
        List<String> urls = new ArrayList<>();
        for (Long assetId : referenceAssetIds) {
            if (assetId == null) {
                continue;
            }
            AssetItem asset = assetService.getAsset(assetId);
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
        if (trimmed.startsWith("/") && StringUtils.hasText(uploadProperties.effectivePublicBaseUrl())) {
            String resolved = uploadProperties.effectivePublicBaseUrl() + trimmed;
            if (isPublicHttpUrl(resolved)) {
                return resolved;
            }
        }
        throw new BusinessException(
                40000,
                "参考图必须是豆包可访问的公网图片 URL；当前资产是本地预览地址。请配置 huashuo.upload.public-base-url，或先不选择参考图直接生成。"
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

    private boolean hasDefaultAvatar(Long projectId) {
        LambdaQueryWrapper<AvatarProfileEntity> w = new LambdaQueryWrapper<>();
        w.eq(AvatarProfileEntity::getProjectId, projectId)
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
            case "FAILED", "RETRYABLE" -> 0;
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
