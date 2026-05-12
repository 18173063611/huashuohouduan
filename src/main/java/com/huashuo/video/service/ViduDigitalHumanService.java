package com.huashuo.video.service;

import com.huashuo.video.DTO.DigitalHumanDTO;
import com.huashuo.video.DTO.DigitalHumanGenerateResponse;
import com.huashuo.video.DTO.DigitalHumanTaskDetailResponse;

public interface ViduDigitalHumanService {

    DigitalHumanGenerateResponse generate(DigitalHumanDTO request, String traceId, Long ownerUserId,
                                          String idempotencyKey);

    DigitalHumanTaskDetailResponse getGenerateTask(Long taskId);
}
