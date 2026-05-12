package com.huashuo.video.service;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;

public interface VideoAsyncTaskService {

    TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId);

    TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId);

    TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId);

    TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId);
}
