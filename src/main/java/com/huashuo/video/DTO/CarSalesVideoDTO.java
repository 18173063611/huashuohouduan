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
    /** off / auto / upload；upload 表示字幕只允许后期烧录，生成模型不得在画面里生成字幕文字。 */
    private String subtitleMode;
    /** 字幕识别语言，用于生成后自动字幕。 */
    private String subtitleLanguage;
    /** auto / audio_recognition / script_timeline；控制字幕时间轴来源。 */
    private String subtitleTimingMode;
    /** auto / audio_master / visual_master；控制最终音画时长对齐策略。 */
    private String syncStrategy;
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
    /** 数字人形象图片，用作销售顾问/主播参考图参与 Seedance 生成。 */
    private String hostImageUrl;
    /** 是否允许虚拟人物/销售顾问出镜；false 时生成提示词会明确避免人物出镜。 */
    private Boolean hostAppearanceEnabled;
    /** 可选成片/口播视频素材，仅作为后续混剪或风格提示参考，不参与图生视频参考图。 */
    private String hostVideoUrl;
    private List<Long> sourceAssetIds;
    private String renderMode;
    private String aspectRatio;
    private List<Long> quickAssetIds;
    private List<AssetRoleBinding> assetRoleBindings;
    /** 多车型对比输入：每个车型素材包作为独立输入单元。 */
    private List<CarPackage> carPackages;

    /** 生成片段数，默认 4，后端限制 1~12。 */
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
        /** top / middle / bottom */
        private String position;
    }
}
