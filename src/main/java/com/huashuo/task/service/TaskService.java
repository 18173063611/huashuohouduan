package com.huashuo.task.service;



import com.huashuo.task.vo.TaskItem;

import com.huashuo.task.vo.TaskResultResponse;

import com.huashuo.task.vo.TaskSummaryResponse;



import java.util.List;

import java.util.OptionalLong;



/**

 * 平台任务服务：负责创建任务占位、更新状态并将 input/output 和资产关联写回任务记录。

 */

public interface TaskService {



    TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId, Long ownerUserId);

    TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId, Long ownerUserId,
                        String modelCode, Long creditCost, String idempotencyKey);



    void startTask(long taskId);

    /**
     * 执行中更新进度（0-100）；仅 {@code RUNNING} 有效，且不会回退已写入的进度。
     */
    void updateTaskProgress(long taskId, int progress);

    /**
     * 执行中更新进度并写入阶段性输出。用于多阶段任务先暴露已完成片段、预览资产等。
     */
    void updateTaskProgress(long taskId, int progress, String outputJson);

    void completeTask(long taskId, String outputJson);

    /**
     * 鐢ㄤ簬鎴愬姛浠诲姟鐨勭粨鏋滃啀缂栬緫锛堜緥濡傛苯杞﹂攢鍞垚鐗囨浛鎹㈠崟娈靛悗閲嶆柊鎷兼帴锛夈€?
     */
    TaskItem replaceSuccessfulTaskResult(long taskId, String outputJson, OptionalLong viewer);



    default void failTask(long taskId, String errorMessage) {
        failTask(taskId, errorMessage, false, true);
    }

    default void failTask(long taskId, String errorMessage, boolean retryable) {
        failTask(taskId, errorMessage, retryable, true);
    }

    /**
     * @param refundCredits {@code false} 表示第三方已受理后失败等场景，按产品规则不自动退款（管理员可手工补偿）。
     */
    void failTask(long taskId, String errorMessage, boolean retryable, boolean refundCredits);



    /**
     * AI 任务消费者自动重试时累加 retry_count，独立于 {@link #retryTask}（不修改状态）。
     */
    void incrementRetryCount(long taskId);



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

    TaskResultResponse getTaskResultForViewer(long taskId, OptionalLong viewer);

}
