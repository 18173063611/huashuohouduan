package com.huashuo.upload;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.ProjectService;
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
public class UploadService {

    private final UploadProperties uploadProperties;
    private final UploadRepository uploadRepository;
    private final ProjectService projectService;

    public UploadService(UploadProperties uploadProperties, UploadRepository uploadRepository,
                            ProjectService projectService) {
        this.uploadProperties = uploadProperties;
        this.uploadRepository = uploadRepository;
        this.projectService = projectService;
    }

    @Transactional
    public UploadedFileItem upload(Long projectId, MultipartFile file) {
        // 上传必须绑定已有项目，避免后续资产中心出现无归属文件。
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
        // 存储文件名由后端生成，避免原始文件名重复或包含不安全路径字符。
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
        // previewUrl 只暴露可访问地址，不把服务端真实目录结构作为前端依赖。
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

    public List<UploadedFileItem> listProjectFiles(Long projectId) {
        projectService.getProject(projectId);
        return uploadRepository.findByProjectId(projectId);
    }
}
