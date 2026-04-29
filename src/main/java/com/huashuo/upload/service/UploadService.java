package com.huashuo.upload.service;

import com.huashuo.upload.vo.UploadedFileItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UploadService {

    UploadedFileItem upload(Long projectId, MultipartFile file);

    List<UploadedFileItem> listProjectFiles(Long projectId);
}
