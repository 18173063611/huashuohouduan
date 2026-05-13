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
 *
 * <p>每个公开方法均提供「带任务上下文」的重载，由 Controller 解析用户与项目后传入，
 * 在创建/轮询 Ark 任务前先通过 {@code TaskService.createTask} 写入本地任务台账并按
 * {@code ai_billing_step_config} 预扣积分；老版本调用方仍可使用不带上下文的方法签名，
 * 默认匿名调用（不扣费）。</p>
 */
public interface VideoService {

    /**
     * 文生视频：仅根据文本提示词生成视频。
     */
    default VideoTaskVO generateText(TextDTO request) {
        return generateText(request, null, null, null, null);
    }

    VideoTaskVO generateText(TextDTO request, Long ownerUserId, Long projectId, String traceId, String idempotencyKey);

    /**
     * 图生视频-首帧：根据首帧图片 + 可选提示词生成视频。
     */
    default VideoTaskVO generateFirstFrame(ImageDTO request) {
        return generateFirstFrame(request, null, null, null, null);
    }

    VideoTaskVO generateFirstFrame(ImageDTO request, Long ownerUserId, Long projectId, String traceId, String idempotencyKey);

    /**
     * 图生视频-首尾帧：根据首帧图、尾帧图 + 可选提示词生成视频。
     */
    default VideoTaskVO generateFirstLastFrame(ImageFirstLastFrameDTO request) {
        return generateFirstLastFrame(request, null, null, null, null);
    }

    VideoTaskVO generateFirstLastFrame(ImageFirstLastFrameDTO request, Long ownerUserId, Long projectId,
                                       String traceId, String idempotencyKey);

    /**
     * 图生视频-参照图：根据 1~N 张参考图 + 可选提示词生成视频。
     * 注意：Seedance 1.5 pro 不支持参照图模式，默认使用 Seedance 1.0 lite i2v。
     */
    default VideoTaskVO generateReference(ImageReferenceDTO request) {
        return generateReference(request, null, null, null, null);
    }

    VideoTaskVO generateReference(ImageReferenceDTO request, Long ownerUserId, Long projectId,
                                  String traceId, String idempotencyKey);
}
