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
    @Size(max = 45, message = "carImageUrls 最多 45 张图片")
    private List<String> carImageUrls;

    /** car_sales / multi_car_compare；多车型对比时会强隔离每个车型素材包。 */
    private String taskMode;

    private String subtitle;    // 字幕
    /** off / auto / custom/upload；自定义字幕只允许后期烧录，生成模型不得在画面里生成字幕文字。 */
    private String subtitleMode;
    /** 字幕识别语言，用于生成后自动字幕。 */
    private String subtitleLanguage;
    /** auto / audio_recognition / script_timeline；控制字幕时间轴来源。 */
    private String subtitleTimingMode;
    /** auto / audio_master / visual_master；控制最终音画时长对齐策略。 */
    private String syncStrategy;
    /** 最终成片字幕烧录样式配置；字幕文本和时间轴仍由 subtitle/subtitleMode 决定。 */
    private TextOverlay subtitleOverlay;
    /** 最终成片大字报文案叠加配置。 */
    private TextOverlay headlineOverlay;

    private String brandModel;
    private String sellingPoints;
    private String audience;
    private String callToAction;

    /** 来自前序阶段的文案、分镜或对标分析结果。 */
    private String scriptContext;

    /** 额外镜头风格或生成要求。 */
    private String prompt;

    private String audioUrl;
    /** none 不使用音频；post_mix 生成后替换/混入口播；reference 作为 Seedance 2.0 参考音频；model_native 由视频模型按文案生成原生音频。 */
    private String audioMode;
    /** 背景音乐音频，仅作为 BGM 混入，不作为口播、字幕或口型来源。 */
    private String bgmUrl;
    /** user_audio | model_native | none；auto_tts 仅保留兼容旧请求。 */
    private String voicePolicy;
    /** auto | benchmark | manual；用于前端导入任务时恢复口播文案来源选择。 */
    private String voiceTextSource;
    /** 最终口播文案；当前阶段用于诊断和后续 TTS 编排，不直接作为字幕烧录来源。 */
    private String finalVoiceText;
    /** true when finalVoiceText is explicit user copy; do not fill it from storyboard voice lines. */
    private Boolean strictVoiceText;
    /** 后续自动 TTS 产物资产；已有时可复用为最终口播音频。 */
    private Long generatedVoiceAssetId;
    private String generatedVoiceUrl;
    /** 自动 TTS 可选配置；不传时使用当前用户默认音色与默认语速/音量/音调。 */
    private Long autoTtsVoiceId;
    private Double autoTtsSpeed;
    private Double autoTtsVolume;
    private Integer autoTtsPitch;
    /** 模型原生音频风格控制：不走独立 TTS，只作为 Seedance 提示词约束。 */
    private String nativeVoiceLanguage;
    private String nativeVoiceStyle;
    private String nativeSpeechStyle;
    /** 前端分镜清洗时已忽略的字段摘要，用于诊断日志。 */
    private List<String> ignoredStoryboardFields;
    /** 一键汽车销售模板分类，例如 family_space / smart_cabin / exterior_style。 */
    private String salesTemplate;
    /** 内测批次号，例如 car-golden-v3-quality-20260608。 */
    private String testBatch;
    /** 固定样本编号，例如 internal-car-test-001。 */
    private String sampleId;
    /** 输出目的，例如 car_sales_golden_path。 */
    private String outputPurpose;
    /** 复盘人；提交阶段可为空。 */
    private String reviewer;
    /** 人工质量评分；提交阶段为空，复盘后可补写。 */
    private Double qualityScore;
    /** 数字人形象图片，用作销售顾问/主播参考图参与 Seedance 生成。 */
    private String hostImageUrl;
    /** 是否允许虚拟人物/销售顾问出镜；false 时生成提示词会明确避免人物出镜。 */
    private Boolean hostAppearanceEnabled;
    /** 前端创作入口名称：AI智能创作 / 爆款对标创作 / 资产复用创作。 */
    private String creationMode;
    /** 前端链路编码：ai-smart / benchmark / asset-reuse。 */
    private String chainType;
    /** 视频类型：standard / digital_human / product_showcase / silent_bgm。 */
    private String videoType;
    /** 是否启用数字人，用于兼容前端高级参数面板。 */
    private Boolean hasDigitalHuman;
    /** 数字人或 host 素材标识。 */
    private String digitalHumanId;
    /** 口播音色或音频素材标识。 */
    private String voiceId;
    /** 语气口吻。 */
    private String tone;
    /** 高级参数语言字段，兼容 nativeVoiceLanguage。 */
    private String language;
    /** 用户选择的目标视频时长，单位秒。 */
    private Integer duration;
    /** 是否开启字幕。 */
    private Boolean enableSubtitle;
    /** 字幕样式摘要。 */
    private String subtitleStyle;
    /** 是否开启大字报。 */
    private Boolean enableBigText;
    /** 大字报样式摘要。 */
    private String bigTextStyle;
    /** 是否开启 BGM。 */
    private Boolean enableBgm;
    /** BGM 风格。 */
    private String bgmStyle;
    /** 是否生成封面。 */
    private Boolean generateCover;
    /** 是否生成标题。 */
    private Boolean generateTitle;
    /** 是否生成简介。 */
    private Boolean generateDescription;
    /** 是否生成标签。 */
    private Boolean generateTags;
    /** 爆款对标原视频标识或链接。 */
    private String benchmarkVideoId;
    /** 本地上传视频标识。 */
    private String uploadedVideoId;
    /** 资产复用链路复用的资产 ID。 */
    private List<Long> reuseAssetIds;
    /** 车型素材或车辆资产标识。 */
    private String vehicleId;
    /** 车型或车辆素材名称。 */
    private String vehicleName;
    /** 可选成片/口播视频素材，仅作为后续混剪或风格提示参考，不参与图生视频参考图。 */
    private String hostVideoUrl;
    private List<Long> sourceAssetIds;
    private String renderMode;
    private String aspectRatio;
    private List<Long> quickAssetIds;
    private List<AssetRoleBinding> assetRoleBindings;
    /** 用户显式选择的封面资产 ID；不影响生成画面，仅用于生成视频资产封面。 */
    private Long coverAssetId;
    /** 用户显式选择的封面 URL；不传时默认使用成片首帧或第一张车辆图。 */
    private String coverUrl;
    /** 多车型对比输入：每个车型素材包作为独立输入单元。 */
    private List<CarPackage> carPackages;
    /** Resource preflight snapshots persisted in task inputJson for replay/debugging. */
    private List<ResourceSnapshot> resourceSnapshots;

    /** 生成片段数，默认 6，后端限制 1~12。 */
    private Integer segmentCount;

    /** 单段时长，默认 5 秒，后端限制 4~15。 */
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
        /** Segment-level digital human lock; copied from the parent request when enabled. */
        private String digitalHumanId;
        private String avatarUrl;
        private String voiceId;
        /** 多车型对比追溯字段：绑定到具体车型素材包。 */
        private String carPackageId;
        private Integer carIndex;
        private String carRole;
        private String compareDimension;
        private String shotPurpose;
    }

    @Data
    public static class AssetRoleBinding {
        private Long assetId;
        private String url;
        private String assetType;
        private String assetRole;
        private String label;
        private String carPackageId;
        private Integer carIndex;
    }

    @Data
    public static class CarPackage {
        private String packageId;
        private Long packageAssetId;
        private String packageName;
        private Integer carIndex;
        /** main / compare / alternative */
        private String role;
        private String brandModel;
        private String color;
        private String sellingPoints;
        private String materialCompleteness;
        private List<String> imageUrls;
        private List<String> sceneImageUrls;
        private List<AssetRoleBinding> assetRoleBindings;
    }

    @Data
    public static class TextOverlay {
        private Boolean enabled;
        private String text;
        private String fontFamily;
        private Integer fontSize;
        private String textColor;
        private String outlineColor;
        /** none / thin / strong */
        private String strokeMode;
        /** top / middle / bottom */
        private String position;
    }
    @Data
    public static class ResourceSnapshot {
        private String resourceType;
        private String sourceUrl;
        private String canonicalUrl;
        private String contentType;
        private Long size;
        private String hash;
        private String checkedAt;
        private String expiresAt;
    }
}
