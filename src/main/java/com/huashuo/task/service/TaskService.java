package com.huashuo.task.service;



import com.huashuo.task.vo.TaskItem;

import com.huashuo.task.vo.TaskSummaryResponse;



import java.util.List;

import java.util.OptionalLong;



/**

 * 平台任务服务：负责创建任务占位、更新状态并将 input/output 和资产关联写回任务记录。

 */

public interface TaskService {



    TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId, Long ownerUserId);



    void startTask(long taskId);

    /**
     * 执行中更新进度（0-100）；仅 {@code RUNNING} 有效，且不会回退已写入的进度。
     */
    void updateTaskProgress(long taskId, int progress);

    void completeTask(long taskId, String outputJson);



    void failTask(long taskId, String errorMessage);



    void failTask(long taskId, String errorMessage, boolean retryable);



    TaskItem retryTask(long taskId, OptionalLong viewer);



    TaskItem cancelTask(long taskId, OptionalLong viewer);



    TaskItem markTaskViewed(long taskId, OptionalLong viewer);



    /**

     * @param projectId 为空：当前登录用户的跨项目任务；非空：该项目下无 owner 的演示任务 + 本人任务

     */

    List<TaskItem> listTasks(OptionalLong viewerUserId, Long projectId, String taskType, String status,

                             Integer pageNo, Integer pageSize);



    TaskSummaryResponse getTaskSummary(OptionalLong viewerUserId, Long projectId);



    TaskItem getTask(long taskId);



    TaskItem getTaskForViewer(long taskId, OptionalLong viewer);

}


