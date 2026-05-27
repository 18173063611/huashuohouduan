package com.huashuo.video.service;

import com.huashuo.video.DTO.QuickRenderRequest;
import com.huashuo.video.DTO.QuickRenderResponse;

public interface QuickRenderService {

    QuickRenderResponse quickRender(QuickRenderRequest request, String traceId, Long ownerUserId,
                                    String idempotencyKey);

    QuickRenderResponse executeForExistingTask(long taskId);
}
