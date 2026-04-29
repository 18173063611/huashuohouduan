package com.huashuo.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.task.entity.TaskEntity;

/**
 * 任务数据访问层：只负责 task 表读写，任务状态规则必须放在 TaskServiceImpl。
 */
public interface TaskMapper extends BaseMapper<TaskEntity> {
}
