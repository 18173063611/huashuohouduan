package com.huashuo.avatar.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("avatar_profile")
/**
 * 数字人形象配置：关联图片资产，记录上传或 AI 生成来源及默认形象状态。
 */
public class AvatarProfileEntity {

    @TableId(value = "avatar_id", type = IdType.AUTO)
    private Long avatarId;

    private Long projectId;

    private Long taskId;

    private Long assetId;

    private String avatarName;

    private String sourceType;

    private String prompt;

    private String referenceAssetIds;

    private String previewUrl;

    private String metadataJson;

    private Integer defaultAvatar;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
