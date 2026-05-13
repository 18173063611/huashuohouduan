package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图生视频-首尾帧 请求参数。
 * 同时传入首帧、尾帧两张图片，模型生成一段从首帧过渡到尾帧的视频。
 */
@Data
public class ImageFirstLastFrameDTO {

    /**
     * 首帧图片地址；对应 role = first_frame。
     */
    @NotBlank(message = "firstFrameUrl 不能为空")
    private String firstFrameUrl;

    /**
     * 尾帧图片地址；对应 role = last_frame。
     */
    @NotBlank(message = "lastFrameUrl 不能为空")
    private String lastFrameUrl;

    /**
     * 文本提示词（可选）。
     */
    private String prompt;

    private String resolution;
    private String ratio;
    private Integer duration;
    private Integer seed;
    private Boolean cameraFixed;
    private Boolean watermark;
    private Boolean generateAudio;
    private String callbackUrl;
    private String safetyIdentifier;

    /**
     * 指定模型，不传使用默认 Seedance 1.5 pro（首尾帧场景受支持）。
     */
    private String model;

    /** 所属项目 ID（可选）。 */
    private Long projectId;
}
