package com.huashuo.upload.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.asset.service.AssetService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.storage.resolve.StoredUrlResolver;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.entity.UploadedFileEntity;
import com.huashuo.upload.mapper.UploadedFileMapper;
import com.huashuo.upload.service.UploadService;
import com.huashuo.upload.vo.UploadedFileItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
/**
 * 上传服务：Multipart 流式直传 TOS，不落本地 ./data/uploads。
 */
public class UploadServiceImpl implements UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadServiceImpl.class);
    private static final DateTimeFormatter DAY_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final String LOCAL_FILE_PATH_PREFIX = "local:";

    private final UploadedFileMapper uploadedFileMapper;
    private final AssetService assetService;
    private final StorageService storageService;
    private final StoredUrlResolver storedUrlResolver;
    private final UploadProperties uploadProperties;

    public UploadServiceImpl(
            UploadedFileMapper uploadedFileMapper,
            AssetService assetService,
            StorageService storageService,
            StoredUrlResolver storedUrlResolver,
            UploadProperties uploadProperties
    ) {
        this.uploadedFileMapper = uploadedFileMapper;
        this.assetService = assetService;
        this.storageService = storageService;
        this.storedUrlResolver = storedUrlResolver;
        this.uploadProperties = uploadProperties;
    }

    @Override
    @Transactional
    public UploadedFileItem upload(Long projectId, MultipartFile file, Long ownerUserId) {
        long started = System.currentTimeMillis();
        UploadedAssetRecord record = uploadAndCreateAsset(projectId, file, ownerUserId);
        logUploadCost("tos", record.uploadedFile(), started);
        return record.uploadedFile();
    }

    @Override
    @Transactional
    public UploadedFileItem uploadLocal(Long projectId, MultipartFile file, Long ownerUserId) {
        long started = System.currentTimeMillis();
        UploadedFileItem uploaded = uploadLocalFile(projectId, file, ownerUserId);
        logUploadCost("local", uploaded, started);
        return uploaded;
    }

    @Override
    @Transactional
    public AssetItem uploadMaterialAsset(Long projectId, MultipartFile file, long ownerUserId, boolean publish) {
        return uploadMaterialAsset(projectId, file, ownerUserId, publish, null);
    }

    @Override
    @Transactional
    public AssetItem uploadMaterialAsset(Long projectId, MultipartFile file, long ownerUserId, boolean publish,
                                         String metadataJson) {
        String carModelBundleContent = readCarModelBundleContent(file, metadataJson);
        UploadedAssetRecord record = uploadAndCreateAsset(projectId, file, ownerUserId, metadataJson);
        AssetItem asset = publish
                ? assetService.publishAsset(record.asset().assetId(), OptionalLong.of(ownerUserId))
                : record.asset();
        if (carModelBundleContent != null) {
            assetService.hideCarModelBundleComponentAssets(carModelBundleContent, OptionalLong.of(ownerUserId));
        }
        return asset;
    }

    private UploadedAssetRecord uploadAndCreateAsset(Long projectId, MultipartFile file, Long ownerUserId) {
        return uploadAndCreateAsset(projectId, file, ownerUserId, null);
    }

    private UploadedAssetRecord uploadAndCreateAsset(Long projectId, MultipartFile file, Long ownerUserId,
                                                     String metadataJson) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40000, "Uploaded file is required");
        }
        UploadResult stored = storageService.upload(file, "upload");
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = stored.filename();
        }

        UploadedFileEntity entity = new UploadedFileEntity();
        entity.setProjectId(projectId);
        entity.setOwnerUserId(ownerUserId);
        entity.setOriginalFileName(originalName.trim());
        entity.setStoredFileName(stored.filename());
        entity.setFilePath(stored.objectKey());
        entity.setPreviewUrl(stored.url());
        entity.setMimeType(stored.contentType());
        entity.setFileSize(stored.size());
        uploadedFileMapper.insert(entity);

        UploadedFileEntity loaded = uploadedFileMapper.selectById(entity.getFileId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load uploaded file after insert");
        }
        UploadedFileItem uploadedFile = toItem(loaded);
        AssetItem asset = assetService.createUploadAsset(
                ownerUserId,
                projectId,
                loaded.getOriginalFileName(),
                uploadedFile.filePath(),
                uploadedFile.previewUrl(),
                uploadedFile.mimeType(),
                uploadedFile.fileSize(),
                metadataJson
        );
        return new UploadedAssetRecord(uploadedFile, asset);
    }

    private UploadedFileItem uploadLocalFile(Long projectId, MultipartFile file, Long ownerUserId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40000, "Uploaded file is required");
        }
        LocalStoredFile stored = saveLocalUpload(file);
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = stored.filename();
        }

        UploadedFileEntity entity = new UploadedFileEntity();
        entity.setProjectId(projectId);
        entity.setOwnerUserId(ownerUserId);
        entity.setOriginalFileName(originalName.trim());
        entity.setStoredFileName(stored.filename());
        entity.setFilePath(LOCAL_FILE_PATH_PREFIX + stored.objectKey());
        entity.setPreviewUrl(stored.previewUrl());
        entity.setMimeType(stored.contentType());
        entity.setFileSize(stored.size());
        uploadedFileMapper.insert(entity);

        UploadedFileEntity loaded = uploadedFileMapper.selectById(entity.getFileId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load uploaded file after insert");
        }
        return toItem(loaded);
    }

    private LocalStoredFile saveLocalUpload(MultipartFile file) {
        if (!StringUtils.hasText(uploadProperties.localRoot())) {
            throw new BusinessException(50001, "未配置本地上传目录，无法使用本地视频上传模式");
        }
        String filename = safeOriginalName(file.getOriginalFilename());
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : "application/octet-stream";
        String objectKey = buildObjectKey("upload", filename);
        Path root = Path.of(uploadProperties.localRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(40000, "非法上传路径");
        }
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(target);
            if (size <= 0) {
                Files.deleteIfExists(target);
                throw new BusinessException(40000, "Invalid file size");
            }
            return new LocalStoredFile(objectKey, previewUrl(objectKey), filename, size, contentType);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(50000, "本地视频保存失败: " + e.getMessage());
        }
    }

    private String readCarModelBundleContent(MultipartFile file, String metadataJson) {
        if (file == null || file.isEmpty() || !isJsonUpload(file)) {
            return null;
        }
        if (!looksLikeCarModelBundle(metadataJson)) {
            String originalName = file.getOriginalFilename();
            if (originalName == null || !originalName.toLowerCase().contains("car-model")
                    && !originalName.contains("车型素材包")) {
                return null;
            }
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return looksLikeCarModelBundle(content) ? content : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean isJsonUpload(MultipartFile file) {
        String contentType = file.getContentType();
        String originalName = file.getOriginalFilename();
        return (contentType != null && contentType.toLowerCase().contains("json"))
                || (originalName != null && originalName.toLowerCase().endsWith(".json"));
    }

    private boolean looksLikeCarModelBundle(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("car_model_bundle")
                || normalized.contains("\"bundletype\"") && normalized.contains("car_model")
                || normalized.contains("车型素材包");
    }

    @Override
    public PageResult<UploadedFileItem> listProjectFiles(OptionalLong viewerUserId, Long projectId, int pageNo,
                                                         int pageSize) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        LambdaQueryWrapper<UploadedFileEntity> w = new LambdaQueryWrapper<>();
        if (projectId != null) {
            w.eq(UploadedFileEntity::getProjectId, projectId);
        }
        applyGlobalUploadVisibility(w, viewerUserId);

        long total = uploadedFileMapper.selectCount(w);
        w.orderByDesc(UploadedFileEntity::getFileId);
        int offset = (safePageNo - 1) * safePageSize;
        w.last("limit " + safePageSize + " offset " + offset);
        List<UploadedFileItem> records = uploadedFileMapper.selectList(w).stream().map(this::toItem).toList();
        return new PageResult<>(records, safePageNo, safePageSize, total);
    }

    private void applyGlobalUploadVisibility(LambdaQueryWrapper<UploadedFileEntity> w, OptionalLong viewerUserId) {
        if (viewerUserId.isEmpty()) {
            w.isNull(UploadedFileEntity::getOwnerUserId);
        } else {
            long uid = viewerUserId.getAsLong();
            w.and(q -> q.isNull(UploadedFileEntity::getOwnerUserId).or().eq(UploadedFileEntity::getOwnerUserId, uid));
        }
    }

    private UploadedFileItem toItem(UploadedFileEntity entity) {
        boolean local = entity.getFilePath() != null && entity.getFilePath().startsWith(LOCAL_FILE_PATH_PREFIX);
        return new UploadedFileItem(
                entity.getFileId(),
                entity.getProjectId(),
                entity.getOwnerUserId(),
                entity.getOriginalFileName(),
                entity.getStoredFileName(),
                entity.getFilePath(),
                local ? entity.getPreviewUrl() : storedUrlResolver.resolveToPublicUrl(entity.getPreviewUrl()),
                entity.getMimeType(),
                entity.getFileSize(),
                entity.getCreatedAt()
        );
    }

    private void logUploadCost(String mode, UploadedFileItem uploadedFile, long started) {
        long costMs = System.currentTimeMillis() - started;
        long size = uploadedFile == null || uploadedFile.fileSize() == null ? -1L : uploadedFile.fileSize();
        if (costMs >= 30_000L) {
            log.warn("Upload completed slowly mode={} fileId={} size={} costMs={}",
                    mode, uploadedFile == null ? null : uploadedFile.fileId(), size, costMs);
        } else {
            log.info("Upload completed mode={} fileId={} size={} costMs={}",
                    mode, uploadedFile == null ? null : uploadedFile.fileId(), size, costMs);
        }
    }

    private String previewUrl(String objectKey) {
        String prefix = uploadProperties.effectivePreviewPrefix();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + objectKey;
    }

    private static String buildObjectKey(String category, String filename) {
        String ext = extensionOf(filename);
        String suffix = ext.isEmpty() ? "" : "." + ext.toLowerCase(Locale.ROOT);
        return category + "/" + LocalDate.now().format(DAY_PATH) + "/" + UUID.randomUUID() + suffix;
    }

    private static String safeOriginalName(String original) {
        String raw = StringUtils.hasText(original) ? original.trim() : "file.bin";
        String base = raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        if (!SAFE_FILENAME.matcher(base).matches()) {
            String ext = extensionOf(base);
            base = "file-" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext.toLowerCase(Locale.ROOT));
        }
        return base;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1);
    }

    private record UploadedAssetRecord(UploadedFileItem uploadedFile, AssetItem asset) {
    }

    private record LocalStoredFile(String objectKey, String previewUrl, String filename, long size, String contentType) {
    }
}
