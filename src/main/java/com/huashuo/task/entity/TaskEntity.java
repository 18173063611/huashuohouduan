package com.huashuo.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task")
/**
 * 任务表实体：记录项目下每一次异步或占位业务任务的类型、状态、输入、输出和错误信息。
 */
public class TaskEntity {

    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;

    private Long projectId;

    /** 发起人：任务中心「我的任务（跨项目）」按此筛选；为空表示历史/演示未绑定用户 */
    private Long ownerUserId;

    private String taskType;

    private String status;

    /** 0-100，供任务中心与详情展示 */
    private Integer progress;

    private String inputJson;

    private String outputJson;

    /** 主结果资产（如 TTS 音频；形象生成取首张图资产 id） */
    private Long resultAssetId;

    private String errorCode;

    private Integer retryCount;

    private String errorMessage;

    private String traceId;

    /** 0/1：用户是否已在任务中心确认查看成功结果 */
    private Integer resultViewed;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
