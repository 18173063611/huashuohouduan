package com.huashuo.upload.service;

import com.huashuo.upload.vo.UploadedFileItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 上传服务接口：定义文件保存、上传记录查询以及上传后生成资产的能力。
 */
public interface UploadService {

    UploadedFileItem upload(Long projectId, MultipartFile file);

    List<UploadedFileItem> listProjectFiles(Long projectId);
}
