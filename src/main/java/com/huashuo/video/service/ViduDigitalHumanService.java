package com.huashuo.video.service;

import com.huashuo.video.DTO.DigitalHumanDTO;
import com.huashuo.video.VO.VideoTaskVO;

public interface ViduDigitalHumanService {

    VideoTaskVO generate(DigitalHumanDTO request, String traceId);
}
