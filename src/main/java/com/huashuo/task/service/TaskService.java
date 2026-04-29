package com.huashuo.task.service;

import com.huashuo.task.vo.TaskItem;

import java.util.List;

/**
 * 任务服务接口：作为所有业务模块写入 task 表的唯一入口，统一定义任务创建、开始、完成和失败。
 */
public interface TaskService {

    TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId);

    TaskItem startTask(Long taskId);

    TaskItem completeTask(Long taskId, String outputJson);

    TaskItem failTask(Long taskId, String errorMessage, boolean retryable);

    List<TaskItem> listProjectTasks(Long projectId);

    TaskItem getTask(Long taskId);
}
