package com.huashuo.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("asset")
/**
 * 资产表实体：保存项目中可被后续流程复用的素材或系统生成结果。
 */
public class AssetEntity {

    @TableId(value = "asset_id", type = IdType.AUTO)
    private Long assetId;

    private Long ownerUserId;

    /**
     * 创建者：上传/生成该资产的用户（用于公共资产作者展示与治理）。
     */
    private Long createdByUserId;

    private Long projectId;

    private Long taskId;

    private String assetType;

    /**
     * 资产类别：素材/模板附件/生成产物等。
     */
    private String kind;

    /**
     * 可见性：PUBLIC=公共资产池；PRIVATE=仅本人。
     */
    private String visibility;

    /**
     * 状态：ACTIVE=可用；REMOVED=已下架（公共资产不物理删除，便于审计与治理）。
     */
    private String status;

    private LocalDateTime publishedAt;

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
