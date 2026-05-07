package com.huashuo.video.service;

import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoVO;

public interface VideoService {
    VideoVO generateText(TextDTO request);

    VideoVO generateImage(ImageDTO request);
}
