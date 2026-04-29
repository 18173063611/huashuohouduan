package com.huashuo.upload.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.upload.entity.UploadedFileEntity;

/**
 * 上传文件数据访问层：只负责 uploaded_file 表的基础 CRUD。
 */
public interface UploadedFileMapper extends BaseMapper<UploadedFileEntity> {
}
