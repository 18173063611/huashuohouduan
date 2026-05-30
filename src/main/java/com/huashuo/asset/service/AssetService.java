package com.huashuo.asset.service;

import com.huashuo.asset.vo.AssetItem;
import com.huashuo.asset.vo.AssetContent;

import java.util.List;
import java.util.OptionalLong;

/**
 * 资产服务接口：定义资产查询、上传资产创建和系统 mock 产物创建等能力。
 */
public interface AssetService {

    AssetItem createUploadAsset(Long ownerUserId, Long projectId, String fileName, String filePath, String fileUrl,
                                String mimeType, long fileSize);

    AssetItem createUploadAsset(Long ownerUserId, Long projectId, String fileName, String filePath, String fileUrl,
                                String mimeType, long fileSize, String metadataJson);

    AssetItem createMockAudioForTask(Long projectId, Long taskId, String voiceCode);

    AssetItem createTtsAudioAsset(Long createdByUserId, Long projectId, Long taskId, String fileName, String absolutePath,
                                  String previewUrl, String mimeType, long fileSize, String metadataJson);

    AssetItem createTtsAudioAsset(Long createdByUserId, Long projectId, Long taskId, String fileName, String absolutePath,
                                  String previewUrl, String mimeType, long fileSize, String sourceType,
                                  String metadataJson);

    AssetItem createAvatarImageAsset(Long ownerUserId, Long projectId, Long taskId, String fileName, String absolutePath,
                                     String previewUrl, String mimeType, long fileSize, String sourceType,
                                     String metadataJson);

    AssetItem createGeneratedVideoAsset(Long ownerUserId, Long projectId, Long taskId, String fileName, String absolutePath,
                                        String previewUrl, String thumbnailUrl, String mimeType, long fileSize,
                                        String sourceType, String metadataJson);

    AssetItem createGeneratedJsonAsset(Long ownerUserId, Long projectId, Long taskId, String fileName,
                                       String jsonContent, String storageCategory, String sourceType,
                                       String metadataJson);

    List<AssetItem> listProjectAssets(OptionalLong viewerUserId, String listScope, Long projectId, String assetType,
                                      String keyword, String sourceType, String assetGroup, String sort);

    AssetItem getAsset(Long assetId);

    AssetItem getAssetForViewer(Long assetId, OptionalLong viewerUserId);

    AssetContent getGeneratedAssetContent(Long assetId, OptionalLong viewerUserId);

    /**
     * 「保存到私有资产」：将尚未归属（owner 为空）的资产认领为当前用户；已属于自己则幂等。
     * 演示类（DEMO）资产不可认领。
     */
    AssetItem saveAssetToUserCollection(Long assetId, OptionalLong viewerUserId);

    /**
     * 发布到公共资产池：仅允许登录用户发布本人私有资产。
     */
    AssetItem publishAsset(Long assetId, OptionalLong viewerUserId);

    /**
     * 下架公共资产：仅允许创建者下架（不物理删除）。
     */
    AssetItem unpublishAsset(Long assetId, OptionalLong viewerUserId);

    AssetItem updateAssetGroup(Long assetId, String assetGroup, OptionalLong viewerUserId);

    AssetItem updateCarModelBundle(Long assetId, String fileName, String contentJson, String metadataJson,
                                   OptionalLong viewerUserId);

    AssetItem updateEditableTextAsset(Long assetId, String fileName, String content, String metadataJson,
                                      OptionalLong viewerUserId);

    void deleteAssetForViewer(Long assetId, OptionalLong viewerUserId);
}
