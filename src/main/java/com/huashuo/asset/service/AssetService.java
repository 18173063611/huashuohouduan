package com.huashuo.asset.service;

import com.huashuo.asset.vo.AssetItem;

import java.util.List;

/**
 * 资产服务接口：定义资产查询、上传资产创建和系统 mock 产物创建等能力。
 */
public interface AssetService {

    AssetItem createUploadAsset(Long projectId, String fileName, String filePath, String fileUrl,
                                String mimeType, long fileSize);

    AssetItem createMockAudioForTask(Long projectId, Long taskId, String voiceCode);

    AssetItem createTtsAudioAsset(Long projectId, Long taskId, String fileName, String absolutePath,
                                  String previewUrl, String mimeType, long fileSize, String metadataJson);

    AssetItem createAvatarImageAsset(Long projectId, Long taskId, String fileName, String absolutePath,
                                     String previewUrl, String mimeType, long fileSize, String sourceType,
                                     String metadataJson);

    List<AssetItem> listProjectAssets(Long projectId, String assetType);

    AssetItem getAsset(Long assetId);
}
