package com.huashuo.script.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("script_version")
public class ScriptVersionEntity {

    @TableId(value = "script_version_id", type = IdType.AUTO)
    private Long scriptVersionId;

    private Long projectId;

    private Integer versionNo;

    private String content;

    private String sourceType;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
