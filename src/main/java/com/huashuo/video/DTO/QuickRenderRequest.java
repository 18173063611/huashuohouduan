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
     * 前端从车型素材包中展开出的车辆参考图。
     * 主要用于 car_model_bundle 本身是 JSON 资产时，避免后端只看到 JSON ID 而缺少真实车辆图。
     */
    private List<String> imageUrls;

    /**
     * 前端从车型/场景素材包中展开出的辅助场景图。
     */
    private List<String> sceneImageUrls;

    /**
     * 前端展开后的素材角色绑定，透传给汽车销售成片，便于按外观、内饰、细节、场景组织镜头。
     */
    private List<CarSalesVideoDTO.AssetRoleBinding> assetRoleBindings;

    /**
     * 可选封面资产。用于用户在前端显式选择视频封面，后端会校验资产可读并优先使用其 thumbnail/fileUrl。
     */
    private Long coverAssetId;

    /**
     * 可选封面 URL。优先级低于 coverAssetId，高于自动首帧/首张素材兜底。
     */
    private String coverUrl;

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
     * 模型原生口播风格。
     */
    private String nativeVoiceStyle;

    /**
     * 模型原生口播节奏。
     */
    private String nativeSpeechStyle;

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
     * 字幕样式配置，透传到汽车销售成片后期字幕烧录。
     */
    private CarSalesVideoDTO.TextOverlay subtitleOverlay;

    /**
     * 大字报样式配置，透传到汽车销售成片后期文字叠加。
     */
    private CarSalesVideoDTO.TextOverlay headlineOverlay;

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
     * 默认一键汽车销售入口为单段连续生成；高级分段模式下表示片段数量。
     */
    private Integer segmentCount;

    /**
     * 单段时长。
     * 默认一键汽车销售入口优先单段连续生成，Seedance 2.0 可传 15 秒；分段模式下表示每段时长。
     */
    private Integer segmentDuration;

    /**
     * AI 智能创作方案确认后的中文分镜。仅用于镜头画面提示；
     * 口播以 finalVoiceText 为准，避免中英文口播污染。
     */
    private List<GeneratedStoryboardShot> generatedStoryboard;

    /**
     * 通用短视频的一句话目标。
     * 一键成片页面不展示大段文案框，仅用于图生视频 prompt 的轻量补充。
     */
    private String goalText;

    /**
     * 内测批次号，例如 car-golden-v3-quality-20260608。
     * 用于把输入、输出和复盘记录关联到同一轮固定样本测试。
     */
    private String testBatch;

    /**
     * 固定样本编号，例如 internal-car-test-001。
     */
    private String sampleId;

    /**
     * 本次输出目的，例如 car_sales_golden_path。
     */
    private String outputPurpose;

    /**
     * 内测复盘人；提交阶段可为空，后续人工评分时再补充。
     */
    private String reviewer;

    /**
     * 所属项目 ID。
     * 会透传到现有任务与资产体系，便于任务中心和资产中心按项目归集。
     */
    private Long projectId;

    /**
     * 是否允许数字人/销售顾问出镜；没有 host_image 素材时仅作为前端配置保留。
     */
    private Boolean hostAppearanceEnabled;

    /**
     * 新版创作中心来源信息：AI智能创作、爆款对标、资产复用等。
     */
    private String creationMode;
    private String chainType;
    private String videoType;

    /**
     * 数字人、声音、语气和语言等高级参数。用于一键成片转入汽车销售生成时补齐导演约束。
     */
    private Boolean hasDigitalHuman;
    private String digitalHumanId;
    private String voiceId;
    private String tone;
    private String language;

    /**
     * 新版页面的目标时长、字幕、大字报、BGM 和发布物料开关。
     */
    private Integer duration;
    private Boolean enableSubtitle;
    private String subtitleStyle;
    private Boolean enableBigText;
    private String bigTextStyle;
    private Boolean enableBgm;
    private String bgmStyle;
    private Boolean generateCover;
    private Boolean generateTitle;
    private Boolean generateDescription;
    private Boolean generateTags;

    /**
     * 链路来源关联 ID，用于复盘、导入和质量对比。
     */
    private String benchmarkVideoId;
    private String uploadedVideoId;
    private List<Long> reuseAssetIds;
    private String vehicleId;
    private String vehicleName;

    @Data
    public static class GeneratedStoryboardShot {
        private Integer index;
        private String visual;
        private String narration;
        private Integer duration;
    }
}
