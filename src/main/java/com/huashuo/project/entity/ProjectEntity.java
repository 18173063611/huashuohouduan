package com.huashuo.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
/**
 * 项目表实体：保存一个数字人视频项目的基础信息和当前状态。
 */
public class ProjectEntity {

    @TableId(value = "project_id", type = IdType.AUTO)
    private Long projectId;

    private String projectName;

    private String description;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
