package com.huashuo.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("template_asset_rel")
public class TemplateAssetRelEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    private Long templateId;

    private Long assetId;

    private String assetRole;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

