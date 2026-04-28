package com.huashuo.upload;

import com.huashuo.common.exception.FwxBusinessException;
import com.huashuo.project.FwxProjectService;
import com.huashuo.upload.vo.FwxUploadedFileItem;
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
public class FwxUploadService {

    private final FwxUploadProperties uploadProperties;
    private final FwxUploadRepository uploadRepository;
    private final FwxProjectService projectService;

    public FwxUploadService(FwxUploadProperties uploadProperties, FwxUploadRepository uploadRepository,
                            FwxProjectService projectService) {
        this.uploadProperties = uploadProperties;
        this.uploadRepository = uploadRepository;
        this.projectService = projectService;
    }

    @Transactional
    public FwxUploadedFileItem upload(Long projectId, MultipartFile file) {
        projectService.getProject(projectId);
        if (file == null || file.isEmpty()) {
            throw new FwxBusinessException(40000, "上传文件不能为空");
        }
        String originalFileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String suffix = "";
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex >= 0) {
            suffix = originalFileName.substring(dotIndex);
        }
        String storedFileName = "fwx-" + UUID.randomUUID() + suffix;
        String datePath = LocalDate.now().toString();
        Path targetDir = Path.of(uploadProperties.localRoot(), datePath);
        Path targetFile = targetDir.resolve(storedFileName);
        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetFile);
        } catch (IOException exception) {
            throw new FwxBusinessException(50000, "文件上传失败：" + exception.getMessage());
        }
        String previewUrl = uploadProperties.previewPrefix() + "/" + datePath + "/" + storedFileName;
        uploadRepository.save(
                projectId,
                originalFileName,
                storedFileName,
                targetFile.toAbsolutePath().toString(),
                previewUrl,
                file.getContentType(),
                file.getSize()
        );
        return uploadRepository.findByProjectId(projectId).get(0);
    }

    public List<FwxUploadedFileItem> listProjectFiles(Long projectId) {
        projectService.getProject(projectId);
        return uploadRepository.findByProjectId(projectId);
    }
}
