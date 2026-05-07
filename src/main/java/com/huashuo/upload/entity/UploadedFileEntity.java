package com.huashuo.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("uploaded_file")
/**
 * 上传文件实体：记录用户上传文件的存储路径、访问地址、原始名称和所属项目。
 */
public class UploadedFileEntity {

    @TableId(value = "file_id", type = IdType.AUTO)
    private Long fileId;

    private Long projectId;

    /** null：历史/公共可见（列表侧与 demo 行为一致） */
    private Long ownerUserId;

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
