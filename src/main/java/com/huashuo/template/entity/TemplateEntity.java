package com.huashuo.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("template")
public class TemplateEntity {

    @TableId(value = "template_id", type = IdType.AUTO)
    private Long templateId;

    private Long ownerUserId;

    private Long createdByUserId;

    private String visibility;

    private String status;

    private LocalDateTime publishedAt;

    private Integer versionNo;

    private String title;

    private String description;

    private Long coverAssetId;

    private String tags;

    private String metadataJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

