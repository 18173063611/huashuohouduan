package com.huashuo.video.service;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesDigitalHumanReplacementRequest;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;

/**
 * Seedance 系列视频生成的统一异步入口：内部走 RabbitMQ + {@code taskService.createTask}，
 * 在任务落库时按 {@code ai_billing_step_config} 预扣积分。
 *
 * <p>每个方法提供两种形态：
 * <ul>
 *   <li>3 参形态（保留旧调用兼容）：不带 projectId / idempotencyKey；</li>
 *   <li>5 参形态：新接入 Authorization / X-Auth-Token / Idempotency-Key / projectId 链路时使用。</li>
 * </ul>
 * 3 参形态默认转发到 5 参形态，{@code projectId=null}，{@code idempotencyKey=null}。
 */
public interface VideoAsyncTaskService {

    default TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId) {
        return createTextVideoTask(request, traceId, ownerUserId, null, null);
    }

    TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId,
                                 Long projectId, String idempotencyKey);

    TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId,
                                 Long projectId, String idempotencyKey, Long creditCost);

    default TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId) {
        return createFirstFrameVideoTask(request, traceId, ownerUserId, null, null);
    }

    TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId,
                                       Long projectId, String idempotencyKey);

    default TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId) {
        return createFirstLastFrameVideoTask(request, traceId, ownerUserId, null, null);
    }

    TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId,
                                           Long projectId, String idempotencyKey);

    default TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId) {
        return createReferenceVideoTask(request, traceId, ownerUserId, null, null);
    }

    TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId,
                                      Long projectId, String idempotencyKey);

    TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId,
                                      Long projectId, String idempotencyKey, Long creditCost);

    default TaskItem createCarSalesVideoTask(CarSalesVideoDTO request, String traceId, Long ownerUserId) {
        return createCarSalesVideoTask(request, traceId, ownerUserId, null, null);
    }

    TaskItem createCarSalesVideoTask(CarSalesVideoDTO request, String traceId, Long ownerUserId,
                                     Long projectId, String idempotencyKey);

    TaskItem createCarSalesSegmentRegenerationTask(long sourceTaskId, int segmentIndex, String traceId,
                                                   Long ownerUserId, String idempotencyKey);

    TaskItem createCarSalesDigitalHumanReplacementTask(long sourceTaskId,
                                                       CarSalesDigitalHumanReplacementRequest request,
                                                       String traceId,
                                                       Long ownerUserId,
                                                       String idempotencyKey);
}
