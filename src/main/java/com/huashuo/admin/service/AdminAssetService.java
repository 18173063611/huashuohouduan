package com.huashuo.admin.service;

import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.response.PageResult;

public interface AdminAssetService {

    PageResult<AssetItem> listAssets(Long ownerUserId, String visibility, String status, String assetType,
                                     String sourceType, String keyword, Integer pageNo, Integer pageSize);

    AssetItem setVisibility(Long assetId, String visibility, AdminOperationContext context);

    AssetItem setStatus(Long assetId, String status, AdminOperationContext context);

    void deleteAsset(Long assetId, AdminOperationContext context);
}
