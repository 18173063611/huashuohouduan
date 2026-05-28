package com.huashuo.upload.service;

import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.response.PageResult;
import com.huashuo.upload.vo.UploadedFileItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.OptionalLong;

/**
 * 上传服务接口：定义文件保存、上传记录查询以及上传后生成资产的能力。
 */
public interface UploadService {

    UploadedFileItem upload(Long projectId, MultipartFile file, Long ownerUserId);

    AssetItem uploadMaterialAsset(Long projectId, MultipartFile file, long ownerUserId, boolean publish);

    AssetItem uploadMaterialAsset(Long projectId, MultipartFile file, long ownerUserId, boolean publish,
                                  String metadataJson);

    PageResult<UploadedFileItem> listProjectFiles(OptionalLong viewerUserId, Long projectId, int pageNo, int pageSize);
}
