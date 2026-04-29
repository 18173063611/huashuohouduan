package com.huashuo.asset.service;

import com.huashuo.asset.vo.AssetItem;

import java.util.List;

public interface AssetService {

    AssetItem createUploadAsset(Long projectId, String fileName, String filePath, String fileUrl,
                                String mimeType, long fileSize);

    AssetItem createMockAudioForTask(Long projectId, Long taskId, String voiceCode);

    List<AssetItem> listProjectAssets(Long projectId, String assetType);

    AssetItem getAsset(Long assetId);
}
