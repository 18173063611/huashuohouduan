package com.huashuo.petvideo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pet_video_work")
public class PetVideoWorkEntity {

    @TableId(value = "work_id", type = IdType.AUTO)
    private Long workId;

    private Long ownerUserId;

    private Long taskId;

    private Long sourceWorkId;

    private String title;

    private String status;

    private String petType;

    private String aspectRatio;

    private Integer durationSeconds;

    private String draftJson;

    private String videoUrl;

    private String coverUrl;

    private Long resultAssetId;

    private LocalDateTime completedAt;

    private String errorCode;

    private String errorMessage;

    private Integer retryable;

    private String providerMetadataJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
