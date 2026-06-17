package com.huashuo.support.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("customer_feedback")
public class CustomerFeedbackEntity {

    @TableId(value = "feedback_id", type = IdType.AUTO)
    private Long feedbackId;

    private Long ownerUserId;

    private String category;

    private String priority;

    private String status;

    private String title;

    private String content;

    private String contact;

    private Long relatedTaskId;

    private Long projectId;

    private String pageUrl;

    private String sourcePath;

    private String userAgent;

    private String attachmentFileIds;

    private String adminReply;

    private String adminNote;

    private Long assigneeAdminId;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
