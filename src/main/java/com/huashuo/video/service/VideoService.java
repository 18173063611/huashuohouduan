package com.huashuo.video.service;

import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.VO.VideoTaskVO;

/**
 * 视频生成服务能力定义。
 * 创建视频任务为同步语义：方法内部会轮询火山方舟「查询视频生成任务」接口，
 * 直到任务进入终态（succeeded / failed / cancelled / expired）或超时；
 * 仅当 videoUrl 非空（任务成功）时才返回结果给上层。
 */
public interface VideoService {

    /**
     * 文生视频：仅根据文本提示词生成视频。
     */
    VideoTaskVO generateText(TextDTO request);

    /**
     * 图生视频-首帧：根据首帧图片 + 可选提示词生成视频。
     */
    VideoTaskVO generateFirstFrame(ImageDTO request);

    /**
     * 图生视频-首尾帧：根据首帧图、尾帧图 + 可选提示词生成视频。
     */
    VideoTaskVO generateFirstLastFrame(ImageFirstLastFrameDTO request);

    /**
     * 图生视频-参照图：根据 1~N 张参考图 + 可选提示词生成视频。
     * 注意：Seedance 1.5 pro 不支持参照图模式，默认使用 Seedance 1.0 lite i2v。
     */
    VideoTaskVO generateReference(ImageReferenceDTO request);
}
