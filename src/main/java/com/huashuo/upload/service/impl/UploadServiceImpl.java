package com.huashuo.upload.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.service.ProjectService;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.entity.UploadedFileEntity;
import com.huashuo.upload.mapper.UploadedFileMapper;
import com.huashuo.upload.service.UploadService;
import com.huashuo.upload.vo.UploadedFileItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class UploadServiceImpl implements UploadService {

    private final UploadProperties uploadProperties;
    private final UploadedFileMapper uploadedFileMapper;
    private final ProjectService projectService;
    private final AssetService assetService;

    public UploadServiceImpl(UploadProperties uploadProperties, UploadedFileMapper uploadedFileMapper,
                             ProjectService projectService, AssetService assetService) {
        this.uploadProperties = uploadProperties;
        this.uploadedFileMapper = uploadedFileMapper;
        this.projectService = projectService;
        this.assetService = assetService;
    }

    @Override
    @Transactional
    public UploadedFileItem upload(Long projectId, MultipartFile file) {
        projectService.getProject(projectId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40000, "Uploaded file is required");
        }
        String originalFileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String suffix = "";
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex >= 0) {
            suffix = originalFileName.substring(dotIndex);
        }
        String storedFileName = "upload-" + UUID.randomUUID() + suffix;
        String datePath = LocalDate.now().toString();
        Path targetDir = Path.of(uploadProperties.localRoot(), datePath);
        Path targetFile = targetDir.resolve(storedFileName);
        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetFile);
        } catch (IOException exception) {
            throw new BusinessException(50000, "File upload failed: " + exception.getMessage());
        }
        String previewUrl = uploadProperties.previewPrefix() + "/" + datePath + "/" + storedFileName;

        UploadedFileEntity entity = new UploadedFileEntity();
        entity.setProjectId(projectId);
        entity.setOriginalFileName(originalFileName);
        entity.setStoredFileName(storedFileName);
        entity.setFilePath(targetFile.toAbsolutePath().toString());
        entity.setPreviewUrl(previewUrl);
        entity.setMimeType(file.getContentType());
        entity.setFileSize(file.getSize());
        uploadedFileMapper.insert(entity);

        UploadedFileEntity loaded = uploadedFileMapper.selectById(entity.getFileId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load uploaded file after insert");
        }
        UploadedFileItem uploadedFile = toItem(loaded);
        assetService.createUploadAsset(
                projectId,
                originalFileName,
                uploadedFile.filePath(),
                uploadedFile.previewUrl(),
                uploadedFile.mimeType(),
                uploadedFile.fileSize()
        );
        return uploadedFile;
    }

    @Override
    public List<UploadedFileItem> listProjectFiles(Long projectId) {
        projectService.getProject(projectId);
        LambdaQueryWrapper<UploadedFileEntity> w = new LambdaQueryWrapper<>();
        w.eq(UploadedFileEntity::getProjectId, projectId)
                .orderByDesc(UploadedFileEntity::getFileId);
        return uploadedFileMapper.selectList(w).stream().map(this::toItem).toList();
    }

    private UploadedFileItem toItem(UploadedFileEntity entity) {
        return new UploadedFileItem(
                entity.getFileId(),
                entity.getProjectId(),
                entity.getOriginalFileName(),
                entity.getStoredFileName(),
                entity.getFilePath(),
                entity.getPreviewUrl(),
                entity.getMimeType(),
                entity.getFileSize(),
                entity.getCreatedAt()
        );
    }
}
