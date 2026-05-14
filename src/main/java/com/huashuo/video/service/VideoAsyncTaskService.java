package com.huashuo.video.service;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;

public interface VideoAsyncTaskService {

    default TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId) {
        return createTextVideoTask(request, traceId, ownerUserId, null);
    }

    TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId, String idempotencyKey);

    default TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId) {
        return createFirstFrameVideoTask(request, traceId, ownerUserId, null);
    }

    TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId, String idempotencyKey);

    default TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId) {
        return createFirstLastFrameVideoTask(request, traceId, ownerUserId, null);
    }

    TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId,
                                           String idempotencyKey);

    default TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId) {
        return createReferenceVideoTask(request, traceId, ownerUserId, null);
    }

    TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId,
                                      String idempotencyKey);
}
