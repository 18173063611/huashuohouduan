package com.huashuo.video.DTO;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文生视频请求参数。
 * 字段命名与火山方舟「创建视频生成任务 API」一致，便于在 ServiceImpl 中直接拼装请求体。
 */
@Data
public class TextDTO {

    /**
     * 文本提示词（必填），描述期望生成的视频内容。
     */
    @NotBlank(message = "prompt 不能为空")
    private String prompt;

    /**
     * 视频分辨率：480p / 720p / 1080p。Seedance 1.5 pro 默认 720p。
     */
    private String resolution;

    /**
     * 视频宽高比：16:9 / 4:3 / 1:1 / 3:4 / 9:16 / 21:9 / adaptive。
     */
    private String ratio;

    /**
     * 视频时长（秒）。Seedance 1.5 pro 支持 [4, 12] 或 -1 由模型自适应。
     */
    private Integer duration;

    /**
     * 随机种子；不传或传 -1 表示由系统随机生成。
     */
    private Integer seed;

    /**
     * 是否固定摄像头。
     */
    private Boolean cameraFixed;

    /**
     * 是否在生成视频上添加水印。
     */
    private Boolean watermark;

    /**
     * 是否生成与画面同步的音频。Seedance 1.5 pro 支持。
     */
    private Boolean generateAudio;

    /**
     * 任务状态变更回调地址（可选）。
     */
    private String callbackUrl;

    /**
     * 终端用户唯一标识（可选），用于平台合规审计。
     */
    private String safetyIdentifier;

    /**
     * 指定视频生成模型；不传则使用配置中的默认模型（Seedance 1.5 pro）。
     */
    private String model;

    /**
     * 所属项目 ID（可选）。若传入会写入本地 task 台账方便项目维度筛选；不传则该任务不归属任何项目。
     */
    private Long projectId;

    /**
     * 业务来源标记。仅用于本地 task inputJson 诊断，不参与第三方请求体拼装。
     */
    private String businessType;

    /**
     * 本地诊断元数据。业务侧可写入 draft 快照、prompt 版本、素材摘要等，provider 调用会忽略该字段。
     */
    private JsonNode diagnosticMetadata;
}
