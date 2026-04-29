package com.huashuo.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("uploaded_file")
public class UploadedFileEntity {

    @TableId(value = "file_id", type = IdType.AUTO)
    private Long fileId;

    private Long projectId;

    private String originalFileName;

    private String storedFileName;

    private String filePath;

    private String previewUrl;

    private String mimeType;

    private Long fileSize;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
