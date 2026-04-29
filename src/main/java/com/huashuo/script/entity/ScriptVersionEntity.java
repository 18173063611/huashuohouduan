package com.huashuo.script.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("script_version")
/**
 * 脚本版本实体：保存项目中的原始文案、改写文案或后续生成脚本内容。
 */
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
