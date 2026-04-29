package com.huashuo.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("asset")
public class AssetEntity {

    @TableId(value = "asset_id", type = IdType.AUTO)
    private Long assetId;

    private Long projectId;

    private Long taskId;

    private String assetType;

    private String fileName;

    private String filePath;

    private String fileUrl;

    private String thumbnailUrl;

    private String mimeType;

    private Long fileSize;

    private String sourceType;

    private String metadataJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
