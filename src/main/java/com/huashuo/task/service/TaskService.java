package com.huashuo.task.service;

import com.huashuo.task.vo.TaskItem;

import java.util.List;

public interface TaskService {

    TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId);

    TaskItem startTask(Long taskId);

    TaskItem completeTask(Long taskId, String outputJson);

    TaskItem failTask(Long taskId, String errorMessage, boolean retryable);

    List<TaskItem> listProjectTasks(Long projectId);

    TaskItem getTask(Long taskId);
}
