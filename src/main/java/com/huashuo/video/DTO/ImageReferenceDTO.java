package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 图生视频-参照图 请求参数。
 * 输入 1~4 张参考图（Seedance 1.0 lite i2v）或 1~9 张（Seedance 2.0 fast），模型基于参考图生成视频。
 * 注意：Seedance 1.5 pro 不支持参考图模式，故此处默认走 1.0 lite i2v。
 */
@Data
public class ImageReferenceDTO {

    /**
     * 参考图列表，每张图片的 role 自动设置为 reference_image。
     */
    @NotNull(message = "imageUrls 不能为空")
    @NotEmpty(message = "imageUrls 至少需要 1 张图片")
    @Size(max = 9, message = "imageUrls 最多 9 张图片")
    private List<String> imageUrls;

    /**
     * 文本提示词（可选）。建议使用 “[图1]xxx，[图2]xxx” 形式以获得更好的指令遵循效果。
     */
    private String prompt;

    private String resolution;
    private String ratio;
    private Integer duration;
    private Integer seed;
    private Boolean watermark;
    private Boolean generateAudio;
    private String callbackUrl;
    private String safetyIdentifier;

    /**
     * 指定模型；不传使用配置中的 reference-model（默认 Seedance 1.0 lite i2v）。
     */
    private String model;
}
