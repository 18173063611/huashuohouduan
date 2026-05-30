package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一键成片请求：前端批量上传素材为资产后，提交资产 ID、识别角色和少量生成策略，
 * 后端将其编排成现有汽车销售、图生视频或数字人生成请求。
 */
@Data
public class QuickRenderRequest {

    /**
     * 用户选择的成片目标。
     * 支持 auto、car_sales、digital_human、general_video、material_mix。
     */
    private String intent;

    /**
     * 本次素材包对应的资产 ID 列表。
     * 后端会按当前登录用户校验每个资产的访问权限。
     */
    @NotEmpty(message = "assetIds 至少需要 1 个素材资产")
    @Size(max = 30, message = "一次最多提交 30 个素材资产")
    private List<Long> assetIds;

    /**
     * 前端识别或用户二次修正后的素材角色。
     * key 为 assetId 字符串，value 为 car_exterior_front、voiceover、bgm 等角色编码。
     */
    private Map<String, String> assetRoles;

    /**
     * 文本类素材的内容快照。
     * 上传 JSON、字幕或脚本文本后，前端可读取内容并随请求提交，便于后端复用分镜、字幕或口播文案。
     */
    private Map<String, String> assetTextContents;

    /**
     * 成片比例。
     * 支持 9:16、16:9、auto；auto 时由底层模型或后端默认策略决定。
     */
    private String aspectRatio;

    /**
     * 字幕策略。
     * 支持 off、auto、upload；upload 会优先使用角色为 subtitle 的文本素材。
     */
    private String subtitleMode;

    /**
     * 字幕识别语言。用于生成后自动字幕识别，默认 zh-CN。
     */
    private String subtitleLanguage;

    /**
     * 文案生成音视频的模型原生口播语言，默认 zh-CN，可选 en-US。
     */
    private String nativeVoiceLanguage;

    /**
     * 用户在一键成片页面手动输入的自定义字幕。
     * subtitleMode=upload 时优先使用该字段，后续用于字幕烧录生成。
     */
    private String customSubtitle;

    /**
     * 页面确认后的最终讲述文案。
     * 当用户选择的讲述语言与原文案不一致时，前端先调用豆包文案改写能力做本地化改写，
     * 用户可继续编辑，后端再把该文案作为模型原生口播和字幕脚本文案。
     */
    private String finalVoiceText;

    /**
     * 是否严格使用 finalVoiceText。
     * true 时不再从分镜旧台词或对标文案中回填口播，避免跨语言或旧文案污染。
     */
    private Boolean strictVoiceText;

    /**
     * 是否将字幕烧录进最终视频。
     * 当前汽车销售链路使用 subtitle 字段控制烧录，保留该字段用于后续更细粒度策略。
     */
    private Boolean burnInSubtitle;

    /**
     * 音频策略。
     * 支持 auto、none、voiceover、bgm；auto 时根据 voiceover、reference_audio、bgm 素材自动决定。
     */
    private String audioPolicy;

    /**
     * 生成模型编码。
     * auto 表示沿用后端配置的默认模型。
     */
    private String model;

    /**
     * 汽车销售成片片段数。
     * 一键成片前端按 8 秒为一个片段选择总时长，例如 6 段约 48 秒。
     */
    private Integer segmentCount;

    /**
     * 单段时长。
     * 一键成片当前固定传 8 秒，保留字段用于后续扩展。
     */
    private Integer segmentDuration;

    /**
     * 通用短视频的一句话目标。
     * 一键成片页面不展示大段文案框，仅用于图生视频 prompt 的轻量补充。
     */
    private String goalText;

    /**
     * 所属项目 ID。
     * 会透传到现有任务与资产体系，便于任务中心和资产中心按项目归集。
     */
    private Long projectId;
}
