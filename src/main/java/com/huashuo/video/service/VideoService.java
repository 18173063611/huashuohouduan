package com.huashuo.video.service;

import com.huashuo.video.VO.VideoTaskVO;
import com.huashuo.video.DTO.CarSalesSegmentComposeRequest;

/**
 * Seedance 视频生成服务能力定义。
 *
 * <p>当前架构下视频任务统一走「Controller -> {@code VideoAsyncTaskService.createTextVideoTask} ->
 * {@code TaskService.createTask}（落库 + 预扣积分） -> RabbitMQ -> {@code SeedanceVideoTaskExecutor}」链路，
 * 因此本接口只暴露**一条**给 MQ 消费侧使用的方法 {@link #executeForExistingTask(long)}：
 * 仅负责调用方舟 Ark 完成视频生成 + 真实用量回写，不再二次创建任务、不再扣费。</p>
 */
public interface VideoService {

    /**
     * 给 MQ executor 用：根据已存在的 taskId 取出任务的 task_type / inputJson / modelCode，
     * 派发到对应 Seedance 端点（文生 / 首帧 / 首尾帧 / 参考图）调用 Ark 同步生成视频，
     * 拿到 videoUrl 后写一次 actual 用量（{@code creditBillingService.settle/recordActual}）并返回结果。
     *
     * <p>本方法**不**再调用 {@code TaskService.createTask}，也不会做积分预扣 / 退款；
     * 任务的 {@code startTask / completeTask / failTask} 由调用方（executor / consumer）负责。</p>
     */
    VideoTaskVO executeForExistingTask(long taskId);

    VideoTaskVO adoptCarSalesRegeneratedSegment(long sourceTaskId, int segmentIndex, long regeneratedTaskId,
                                                Long viewerUserId);

    VideoTaskVO composeCarSalesSegments(long sourceTaskId, CarSalesSegmentComposeRequest request, Long viewerUserId);
}
