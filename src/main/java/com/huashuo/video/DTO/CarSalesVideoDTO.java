package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 汽车销售复合成片请求：基于车辆图片、前序脚本/分镜上下文生成多段短视频，并拼接为完整销售视频。
 */
@Data
public class CarSalesVideoDTO {

    @NotEmpty(message = "carImageUrls 至少需要 1 张车辆图片")
    @Size(max = 9, message = "carImageUrls 最多 9 张图片")
    private List<String> carImageUrls;

    private String brandModel;
    private String sellingPoints;
    private String audience;
    private String callToAction;

    /** 来自前序阶段的文案、分镜或对标分析结果。 */
    private String scriptContext;

    /** 额外镜头风格或生成要求。 */
    private String prompt;

    private String audioUrl;
    /** none 不使用音频；post_mix 生成后替换/混入音频；reference 作为 Seedance 2.0 生成参考音频。 */
    private String audioMode;
    /** 背景音乐音频，仅作为 BGM 混入，不作为口播、字幕或口型来源。 */
    private String bgmUrl;
    /** 前端分镜清洗时已忽略的字段摘要，用于诊断日志。 */
    private List<String> ignoredStoryboardFields;
    /** 数字人形象图片，用作销售顾问/主播参考图参与 Seedance 生成。 */
    private String hostImageUrl;
    /** 可选成片/口播视频素材，仅作为后续混剪或风格提示参考，不参与图生视频参考图。 */
    private String hostVideoUrl;
    private List<Long> sourceAssetIds;

    /** 生成片段数，默认 4，后端限制 2~6。 */
    private Integer segmentCount;

    /** 单段时长，默认 8 秒，后端限制 4~12。 */
    private Integer segmentDuration;

    /** 可选：前端显式传入多段任务结构；不传时后端按汽车销售脚本默认生成。 */
    private List<Scene> scenes;

    private String model;
    private Long projectId;

    @Data
    public static class Scene {
        private Integer segmentIndex;
        private String title;
        /** 轻量分段结构预留：只描述画面，不承载旧台词或 BGM。 */
        private String visualPrompt;
        private String prompt;
        private List<String> imageUrls;
        private String referenceImage;
        /** 仅在没有口播音频时作为文案参考使用。 */
        private String voiceText;
        private Integer duration;
    }
}
