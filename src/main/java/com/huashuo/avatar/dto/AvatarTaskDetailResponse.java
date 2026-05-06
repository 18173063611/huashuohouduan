package com.huashuo.avatar.dto;

import com.huashuo.asset.vo.AssetItem;
import com.huashuo.avatar.vo.AvatarItem;

import java.util.List;

public record AvatarTaskDetailResponse(
        Long taskId,
        Long projectId,
        String taskType,
        String status,
        Integer progress,
        String errorMessage,
        List<AssetItem> imageAssets,
        List<AvatarItem> avatars
) {
}
