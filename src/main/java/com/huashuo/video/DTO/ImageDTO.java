package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图生视频-首帧 请求参数。
 * 仅传入首帧图片，模型从该图片开始生成一段视频。
 */
@Data
public class ImageDTO {

    /**
     * 首帧图片。支持公网 URL、Base64（data:image/png;base64,xxx）、素材 ID（asset://xxx）。
     */
    @NotBlank(message = "imageUrl 不能为空")
    private String imageUrl;

    /**
     * 文本提示词（可选），辅助描述运动、风格、镜头。
     */
    private String prompt;

    /**
     * 视频分辨率：480p / 720p / 1080p。
     */
    private String resolution;

    /**
     * 视频宽高比；Seedance 1.5 pro 图生视频默认 adaptive，会根据首帧图自动适配最近的宽高比。
     */
    private String ratio;

    /**
     * 视频时长（秒）。
     */
    private Integer duration;

    /**
     * 随机种子。
     */
    private Integer seed;

    /**
     * 是否固定摄像头。
     */
    private Boolean cameraFixed;

    /**
     * 是否水印。
     */
    private Boolean watermark;

    /**
     * 是否生成同步音频。
     */
    private Boolean generateAudio;

    /**
     * 任务状态变更回调地址（可选）。
     */
    private String callbackUrl;

    /**
     * 终端用户唯一标识（可选）。
     */
    private String safetyIdentifier;

    /**
     * 指定模型，不传使用默认 Seedance 1.5 pro。
     */
    private String model;
}
