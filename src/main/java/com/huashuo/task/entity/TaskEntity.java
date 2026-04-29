package com.huashuo.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task")
public class TaskEntity {

    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;

    private Long userId;

    private Long projectId;

    private String taskType;

    private String status;

    private String inputJson;

    private String outputJson;

    private Integer retryCount;

    private String errorMessage;

    private String traceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
