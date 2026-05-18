package com.huashuo.upload.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.storage.resolve.StoredUrlResolver;
import com.huashuo.upload.entity.UploadedFileEntity;
import com.huashuo.upload.mapper.UploadedFileMapper;
import com.huashuo.upload.service.UploadService;
import com.huashuo.upload.vo.UploadedFileItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.OptionalLong;

@Service
/**
 * 上传服务：Multipart 流式直传 TOS，不落本地 ./data/uploads。
 */
public class UploadServiceImpl implements UploadService {

    private final UploadedFileMapper uploadedFileMapper;
    private final AssetService assetService;
    private final StorageService storageService;
    private final StoredUrlResolver storedUrlResolver;

    public UploadServiceImpl(
            UploadedFileMapper uploadedFileMapper,
            AssetService assetService,
            StorageService storageService,
            StoredUrlResolver storedUrlResolver
    ) {
        this.uploadedFileMapper = uploadedFileMapper;
        this.assetService = assetService;
        this.storageService = storageService;
        this.storedUrlResolver = storedUrlResolver;
    }

    @Override
    @Transactional
    public UploadedFileItem upload(Long projectId, MultipartFile file, Long ownerUserId) {
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
        assetService.createUploadAsset(
                ownerUserId,
                projectId,
                loaded.getOriginalFileName(),
                uploadedFile.filePath(),
                uploadedFile.previewUrl(),
                uploadedFile.mimeType(),
                uploadedFile.fileSize()
        );
        return uploadedFile;
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
        return new UploadedFileItem(
                entity.getFileId(),
                entity.getProjectId(),
                entity.getOwnerUserId(),
                entity.getOriginalFileName(),
                entity.getStoredFileName(),
                entity.getFilePath(),
                storedUrlResolver.resolveToPublicUrl(entity.getPreviewUrl()),
                entity.getMimeType(),
                entity.getFileSize(),
                entity.getCreatedAt()
        );
    }
}
