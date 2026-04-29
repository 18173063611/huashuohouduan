package com.huashuo.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.project.entity.ProjectEntity;

/**
 * 项目数据访问层：只负责 project 表的基础 CRUD，不承载业务规则。
 */
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
}
