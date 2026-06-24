package com.huashuo.video.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.QuickRenderRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoServiceImplPromptGuardTest {

    private final VideoServiceImpl service = new VideoServiceImpl(
            null,
            "doubao-seedance-1-5-pro",
            "doubao-seedance-reference",
            5,
            1200,
            null,
            new ObjectMapper(),
            null,
            null,
            null,
            null,
            null,
            null,
            "https://ark.cn-beijing.volces.com/api/v3",
            "",
            "doubao-seed-2-0-mini-260215",
            4,
            "ffmpeg",
            "ffprobe",
            ""
    );

    @Test
    void sceneReferenceSanitizerRemovesStoryboardEnvironmentWords() {
        String cleaned = (String) invoke("sanitizeScenePromptForSceneReference",
                new Class<?>[]{String.class, boolean.class},
                "在展厅玻璃墙和瓷砖地面前环绕拍摄车头，突出高级感", false);

        assertThat(cleaned)
                .contains("场景参考图", "平稳小幅环绕")
                .doesNotContain("展厅", "玻璃墙", "瓷砖");
    }

    @Test
    void englishNarrationPromptContainsNoChineseEvenWhenStoryboardIsChinese() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setNativeVoiceLanguage("en-US");
        request.setAudioMode("model_native");
        request.setBrandModel("领克 08");
        request.setSellingPoints("大空间、智能座舱");
        request.setPrompt("在豪华展厅玻璃墙前拍摄");
        request.setHostAppearanceEnabled(false);

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setTitle("展厅开场");
        scene.setVisualPrompt("在展厅玻璃墙和瓷砖地面中环绕拍摄车头");
        scene.setPrompt(scene.getVisualPrompt());
        scene.setVoiceText("This SUV gives you a premium cabin and smart driving confidence.");

        Object imageSelection = sceneImageSelection(
                List.of("https://cdn.test/scene.jpg", "https://cdn.test/car.jpg"),
                List.of("scene_showroom", "car_exterior_front"),
                List.of("场景图", "车头图")
        );

        String prompt = (String) invokeBuildPrompt(request, scene, imageSelection);

        assertThat(prompt)
                .contains("English only", "Scene reference lock", "uploaded scene reference")
                .doesNotContainPattern("\\p{IsHan}")
                .doesNotContain("showroom glass wall", "tile floor");
    }

    @Test
    void chineseCarSalesPromptStaysCompactForVehicleOnlyMaterials() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("auto_tts");
        request.setVoicePolicy("auto_tts");
        request.setBrandModel("\u9886\u514b 06");
        request.setSellingPoints("\u8f66\u5934\u706f\u5149\u3001\u667a\u80fd\u5ea7\u8231\u3001\u540e\u6392\u7a7a\u95f4");
        request.setCallToAction("\u9884\u7ea6\u8bd5\u9a7e\uff0c\u79c1\u4fe1\u9886\u53d6\u5230\u5e97\u6743\u76ca");
        request.setPrompt("\u8f66\u578b\u3001\u5ba2\u6237\u3001\u5356\u70b9\u7684\u8f6c\u5316\u5f15\u5bfc\u5c5e\u4e8e\u6587\u6848\u8865\u5145\uff0c\u753b\u9762\u4e0d\u8981\u5c55\u793a\u53ef\u8bfb\u6587\u5b57");
        request.setHostAppearanceEnabled(false);

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setTitle("\u5185\u9970\u5ea7\u8231");
        scene.setVisualPrompt("\u5c55\u793a\u4e2d\u63a7\u5c4f\u3001\u65b9\u5411\u76d8\u3001\u4eea\u8868\u3001\u5ea7\u8231\u6c1b\u56f4\u548c\u6750\u8d28\uff0c\u955c\u5934\u4ece\u524d\u6392\u7a7a\u95f4\u5e73\u7a33\u626b\u8fc7\u3002");
        scene.setPrompt(scene.getVisualPrompt());
        scene.setVoiceText("\u8fd9\u53f0\u9886\u514b 06 \u7684\u5ea7\u8231\u79d1\u6280\u611f\u548c\u7a7a\u95f4\u611f\u90fd\u5f88\u9002\u5408\u65e5\u5e38\u5bb6\u7528\u3002");

        Object imageSelection = sceneImageSelection(
                List.of("https://cdn.test/car-front.jpg", "https://cdn.test/car-cabin.jpg"),
                List.of("car_exterior_front", "car_interior_dashboard"),
                List.of("\u8f66\u5934\u56fe", "\u5185\u9970\u56fe")
        );

        String prompt = (String) invokeBuildPrompt(request, scene, imageSelection);

        assertThat(prompt.length()).isLessThanOrEqualTo(1600);
        assertThat(prompt)
                .contains("\u9886\u514b 06",
                        "\u5185\u9970\u5ea7\u8231",
                        "\u955c\u5934",
                        "\u8de8\u6bb5\u8fde\u7eed",
                        "\u5206\u955c\u8fb9\u754c",
                        "\u53c2\u8003\u56fe",
                        "\u97f3\u9891",
                        "\u753b\u9762\u7528\u9014",
                        "\u753b\u9762\u5b89\u5168\u533a",
                        "\u753b\u9762\u6587\u5b57\u786c\u6027\u89c4\u5219",
                        "\u4ea7\u54c1\u7ea7\u8f66\u8f86\u5c55\u793a\u753b\u9762",
                        "\u540e\u671f\u5408\u6210")
                .doesNotContain("\u753b\u9762\u6587\u5b57\u786c\u6027\u7981\u4ee4",
                        "\u6700\u9ad8\u4f18\u5148\u7ea7\u4eba\u7269\u7981\u4ee4",
                        "\u80cc\u666f\u97f3\u4e50\u786c\u6027\u7981\u4ee4",
                        "\u7edd\u5bf9\u4e0d\u5f97\u51fa\u73b0",
                        "\u65e0\u5b57\u5e55",
                        "\u65e0\u4eba\u50cf",
                        "\u4eba\u7269\u5904\u7406",
                        "\u624b\u90e8",
                        "\u8def\u4eba");
    }

    @Test
    void chineseSeedancePromptDropsUserTextOverlayCues() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("post_mix");
        request.setBrandModel("\u5409\u5229 2026\u5409\u5229\u725b\u4ed4");
        request.setSellingPoints("\u6f6e\u73a9\u5916\u89c2\u3001\u7075\u6d3b\u7a7a\u95f4\u3001\u65e5\u5e38\u901a\u52e4");
        request.setPrompt("\u5f3a\u5b57\u5e55\u5927\u5b57\u62a5\uff0c\u4ef7\u683c\u6743\u76ca\u6587\u6848\uff0c\u6807\u9898\u5361\u51b2\u51fb\u611f");
        request.setHostAppearanceEnabled(false);

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setTitle("\u5916\u89c2\u5f00\u573a");
        scene.setVisualPrompt("\u955c\u5934\u610f\u56fe=\u5916\u89c2\u5f00\u573a\uff1b\u5b57\u5e55/\u5927\u5b57\u62a5\u540e\u671f\u5efa\u8bae=\u95e8\u5e97\u5230\u5e97\u6743\u76ca\uff1b\u6267\u884c\u8bf4\u660e=\u8f66\u8eab\u5b8c\u6574\u5165\u955c");
        scene.setPrompt(scene.getVisualPrompt());
        scene.setVoiceText("\u8fd9\u53f0\u5409\u5229\u725b\u4ed4\uff0c\u5916\u89c2\u591f\u4e2a\u6027\uff0c\u65e5\u5e38\u901a\u52e4\u4e5f\u7075\u6d3b\u3002");

        Object imageSelection = sceneImageSelection(
                List.of("https://cdn.test/niu-front.jpg"),
                List.of("car_exterior_front"),
                List.of("\u5916\u89c2\u56fe")
        );

        String prompt = (String) invokeBuildPrompt(request, scene, imageSelection);

        assertThat(prompt)
                .contains("\u753b\u9762\u6587\u5b57\u786c\u6027\u89c4\u5219", "\u5409\u5229 2026\u5409\u5229\u725b\u4ed4")
                .doesNotContain("\u5f3a\u5b57\u5e55\u5927\u5b57\u62a5",
                        "\u4ef7\u683c\u6743\u76ca\u6587\u6848",
                        "\u6807\u9898\u5361\u51b2\u51fb\u611f",
                        "\u5b57\u5e55/\u5927\u5b57\u62a5\u540e\u671f\u5efa\u8bae");
    }

    @Test
    void verticalSrtSubtitleIsWrappedAndFontSizeIsCapped() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAspectRatio("9:16");
        CarSalesVideoDTO.TextOverlay overlay = new CarSalesVideoDTO.TextOverlay();
        overlay.setEnabled(true);
        overlay.setFontSize(34);
        overlay.setPosition("bottom");
        request.setSubtitleOverlay(overlay);

        String longLine = "\u5e74\u8f7b\u4eba\u7b2c\u4e00\u53f0\u6f6e\u9177\u5ea7\u9a7e\u6765\u54af2026\u6b3e\u5409\u5229\u725b\u4ed4\u5927\u7a7a\u95f4\u5168\u5bb6\u51fa\u884c\u4e5f\u8212\u670d\u559c\u6b22\u7684\u670b\u53cb\u8d76\u7d27\u5230\u5e97\u54a8\u8be2\u8bd5\u9a7e\u54e6";
        String srt = "1\n00:00:24,000 --> 00:00:30,000\n" + longLine + "\n\n"
                + "2\n00:00:30,000 --> 00:00:30,500\n\uff0c\n\n";

        Object layout = invoke("subtitleLayout", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Method fontSizeMethod = layout.getClass().getDeclaredMethod("srtFontSize");
        fontSizeMethod.setAccessible(true);
        String wrapped = (String) invoke("wrapSrtSubtitleLines",
                new Class<?>[]{String.class, CarSalesVideoDTO.class}, srt, request);

        assertThat((Integer) fontSizeMethod.invoke(layout)).isLessThanOrEqualTo(12);
        assertThat(wrapped).doesNotContain(longLine);
        assertThat(wrapped).doesNotContain("\n\uff0c\n");
        assertThat(wrapped).contains("00:00:24,000 --> 00:00:30,000");
        assertThat(wrapped.split("\\n").length).isGreaterThan(4);
    }

    @Test
    void quickRenderUploadedVoiceKeepsGeneratedStoryboardScenes() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setSegmentCount(2);
        request.setSegmentDuration(5);
        request.setFinalVoiceText("第一段介绍外观。第二段介绍内饰。");
        request.setAudioPolicy("auto");
        request.setGeneratedStoryboard(List.of(
                quickShot(1, "镜头意图=外观开场；景别=全景；运镜=慢速推进；主体/场景=车头和车身线条", "第一段介绍外观。", 5),
                quickShot(2, "镜头意图=内饰展示；景别=中近景；运镜=平稳横移；主体/场景=座椅和中控", "第二段介绍内饰。", 5)
        ));

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null),
                quickMaterial(quickAsset(2L, "AUDIO", "audio/mpeg", "voice.mp3", "https://cdn.test/voice.mp3"),
                        "voiceover", null)
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getAudioMode()).isEqualTo("post_mix");
        assertThat(dto.getScenes()).hasSize(2);
        assertThat(dto.getScenes().get(0).getVisualPrompt()).contains("外观开场");
        assertThat(dto.getScenes().get(1).getVisualPrompt()).contains("内饰展示");
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getVoiceText)
                .containsExactly("第一段介绍外观。", "第二段介绍内饰。");
    }

    @Test
    void quickRenderCarSalesDefaultsToLegacySixByFiveAndPreservesAdvancedFields() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("auto");
        request.setCreationMode("AI智能创作");
        request.setChainType("ai-smart");
        request.setVideoType("digital_human");
        request.setHasDigitalHuman(true);
        request.setDigitalHumanId("host-1");
        request.setVoiceId("voice-1");
        request.setTone("professional_sales_consultant");
        request.setLanguage("zh-CN");
        request.setDuration(30);
        request.setEnableSubtitle(true);
        request.setSubtitleStyle("bottom-safe-zone");
        request.setEnableBigText(true);
        request.setBigTextStyle("top-safe-zone");
        request.setBgmStyle("bright_corporate");
        request.setVehicleId("vehicle-1");
        request.setVehicleName("Legacy SUV");

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null),
                quickMaterial(quickAsset(2L, "IMAGE", "image/jpeg", "host.jpg", "https://cdn.test/host.jpg"),
                        "host_image", null)
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getSegmentCount()).isEqualTo(6);
        assertThat(dto.getSegmentDuration()).isEqualTo(5);
        assertThat(dto.getScenes()).hasSize(6);
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getDuration).containsOnly(5);
        assertThat(dto.getCreationMode()).isEqualTo("AI智能创作");
        assertThat(dto.getChainType()).isEqualTo("ai-smart");
        assertThat(dto.getVideoType()).isEqualTo("digital_human");
        assertThat(dto.getHasDigitalHuman()).isTrue();
        assertThat(dto.getDigitalHumanId()).isEqualTo("host-1");
        assertThat(dto.getVoiceId()).isEqualTo("voice-1");
        assertThat(dto.getTone()).isEqualTo("professional_sales_consultant");
        assertThat(dto.getLanguage()).isEqualTo("zh-CN");
        assertThat(dto.getDuration()).isEqualTo(30);
        assertThat(dto.getEnableSubtitle()).isTrue();
        assertThat(dto.getSubtitleStyle()).isEqualTo("bottom-safe-zone");
        assertThat(dto.getEnableBigText()).isTrue();
        assertThat(dto.getBigTextStyle()).isEqualTo("top-safe-zone");
        assertThat(dto.getBgmStyle()).isEqualTo("bright_corporate");
        assertThat(dto.getVehicleId()).isEqualTo("vehicle-1");
        assertThat(dto.getVehicleName()).isEqualTo("Legacy SUV");
    }

    @Test
    void quickRenderDigitalHumanLocksSameAvatarAndVoiceAcrossScenes() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("auto");
        request.setSegmentCount(3);
        request.setSegmentDuration(5);
        request.setVideoType("digital_human");
        request.setHasDigitalHuman(true);
        request.setHostAppearanceEnabled(true);
        request.setDigitalHumanId("dh1");
        request.setVoiceId("voice-locked");

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null),
                quickMaterial(quickAsset(2L, "IMAGE", "image/jpeg", "avatar.jpg", "https://cdn.test/avatar.jpg"),
                        "host_image", null)
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getHasDigitalHuman()).isTrue();
        assertThat(dto.getHostAppearanceEnabled()).isTrue();
        assertThat(dto.getDigitalHumanId()).isEqualTo("dh1");
        assertThat(dto.getHostImageUrl()).isEqualTo("https://cdn.test/avatar.jpg");
        assertThat(dto.getScenes()).hasSize(3);
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getDigitalHumanId).containsOnly("dh1");
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getAvatarUrl)
                .containsOnly("https://cdn.test/avatar.jpg");
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getVoiceId).containsOnly("voice-locked");
    }

    @Test
    void quickRenderDigitalHumanUsesRequestAvatarUrlWhenHostAssetWasNotSubmitted() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("auto");
        request.setSegmentCount(2);
        request.setSegmentDuration(5);
        request.setHasDigitalHuman(true);
        request.setHostAppearanceEnabled(true);
        request.setDigitalHumanId("dh-from-ui");
        request.setAvatarUrl("https://cdn.test/avatar-from-ui.jpg");

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null)
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getHostImageUrl()).isEqualTo("https://cdn.test/avatar-from-ui.jpg");
        assertThat(dto.getScenes()).hasSize(2);
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getDigitalHumanId).containsOnly("dh-from-ui");
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getAvatarUrl)
                .containsOnly("https://cdn.test/avatar-from-ui.jpg");
    }

    @Test
    void quickRenderDigitalHumanRejectsMissingDigitalHumanIdInsteadOfFallbackAvatar() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("auto");
        request.setHasDigitalHuman(true);
        request.setHostAppearanceEnabled(true);

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null),
                quickMaterial(quickAsset(2L, "IMAGE", "image/jpeg", "avatar.jpg", "https://cdn.test/avatar.jpg"),
                        "host_image", null)
        );

        assertThatThrownBy(() -> buildQuickCarSalesRequest(request, materials))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(com.huashuo.common.exception.BusinessException.class)
                .hasMessageContaining("digitalHumanId is required");
    }

    @Test
    void quickRenderControlConstraintsDoNotBecomeNarrationOrSubtitles() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("auto");
        request.setSegmentCount(2);
        request.setSegmentDuration(5);
        request.setGoalText("车型：家庭SUV；核心卖点：大空间；30秒完整分镜结构。车辆一致性。字幕/大字报安全区。");
        request.setFinalVoiceText("30秒完整分镜结构。车辆一致性。字幕/大字报安全区。");
        request.setCustomSubtitle("30秒完整分镜结构。");

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null)
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getFinalVoiceText()).isNull();
        assertThat(dto.getSubtitle()).isEqualTo("自动生成");
        for (CarSalesVideoDTO.Scene scene : dto.getScenes()) {
            assertThat(scene.getVoiceText())
                    .doesNotContain("30秒完整分镜结构", "车辆一致性", "字幕/大字报安全区");
        }
        assertThat(dto.getScenes().get(0).getVoiceText()).contains("家庭SUV", "大空间");
    }

    @Test
    void benchmarkQuickRenderKeepsNoVoicePolicyAndUsesBenchmarkOnlyAsReference() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("none");
        request.setSegmentCount(6);
        request.setSegmentDuration(5);
        request.setGoalText("vehicle=Legacy SUV; sellingPoints=space, lights, CTA");
        request.setGeneratedStoryboard(List.of(
                quickShot(1, "exterior opening", null, 5),
                quickShot(2, "side profile", null, 5),
                quickShot(3, "interior space", null, 5),
                quickShot(4, "lighting detail", null, 5),
                quickShot(5, "driving scene", null, 5),
                quickShot(6, "store CTA", null, 5)
        ));

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null),
                quickMaterial(quickAsset(2L, "JSON", "application/json", "benchmark.json", "https://cdn.test/benchmark.json"),
                        "benchmark_json", "ASR OVERRIDE TEXT SHOULD STAY REFERENCE ONLY")
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getAudioMode()).isEqualTo("none");
        assertThat(dto.getVoicePolicy()).isEqualTo("none");
        assertThat(dto.getFinalVoiceText()).isNull();
        assertThat(dto.getScenes()).hasSize(6);
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getDuration).containsOnly(5);
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getVisualPrompt)
                .containsExactly("exterior opening", "side profile", "interior space",
                        "lighting detail", "driving scene", "store CTA");
        assertThat(dto.getScenes()).extracting(CarSalesVideoDTO.Scene::getVoiceText).containsOnlyNulls();
        assertThat(dto.getScriptContext()).contains("ASR OVERRIDE TEXT SHOULD STAY REFERENCE ONLY");
        assertThat(dto.getPrompt()).doesNotContain("ASR OVERRIDE TEXT SHOULD STAY REFERENCE ONLY");
    }

    @Test
    void quickRenderExternalAudioPolicyUsesSingleExternalPipeline() throws Exception {
        QuickRenderRequest request = new QuickRenderRequest();
        request.setAudioPolicy("EXTERNAL_AUDIO");
        request.setDuration(30);

        List<Object> materials = List.of(
                quickMaterial(quickAsset(1L, "IMAGE", "image/jpeg", "front.jpg", "https://cdn.test/front.jpg"),
                        "car_exterior_front", null)
        );

        CarSalesVideoDTO dto = buildQuickCarSalesRequest(request, materials);

        assertThat(dto.getAudioMode()).isEqualTo("auto_tts");
        assertThat(dto.getVoicePolicy()).isEqualTo("auto_tts");
        assertThat(dto.getAudioUrl()).isNull();
        assertThat(dto.getSegmentCount()).isEqualTo(6);
        assertThat(dto.getSegmentDuration()).isEqualTo(5);
    }

    @Test
    void audioModeAliasesNormalizeToSeparatedPipelines() {
        CarSalesVideoDTO external = new CarSalesVideoDTO();
        external.setAudioMode("EXTERNAL_AUDIO");
        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, external);

        assertThat(external.getAudioMode()).isEqualTo("auto_tts");
        assertThat(external.getVoicePolicy()).isEqualTo("auto_tts");
        assertThat(invoke("shouldGenerateNativeAudio", new Class<?>[]{CarSalesVideoDTO.class}, external)).isEqualTo(false);

        CarSalesVideoDTO nativeAudio = new CarSalesVideoDTO();
        nativeAudio.setAudioMode("VIDEO_NATIVE_AUDIO");
        nativeAudio.setAudioUrl("https://cdn.test/voice.mp3");
        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, nativeAudio);

        assertThat(nativeAudio.getAudioMode()).isEqualTo("model_native");
        assertThat(nativeAudio.getVoicePolicy()).isEqualTo("model_native");
        assertThat(nativeAudio.getAudioUrl()).isNull();
        assertThat(invoke("shouldGenerateNativeAudio", new Class<?>[]{CarSalesVideoDTO.class}, nativeAudio)).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void targetDurationSplitFillsThirtySecondsWhenSingleSceneHitsModelMax() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setDuration(30);
        request.setSegmentCount(1);
        request.setSegmentDuration(30);

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setSegmentIndex(1);
        scene.setTitle("opening");
        scene.setVisualPrompt("show the same SUV");
        scene.setDuration(30);

        List<CarSalesVideoDTO.Scene> scenes = (List<CarSalesVideoDTO.Scene>) invoke(
                "normalizeScenesForTargetDuration",
                new Class<?>[]{CarSalesVideoDTO.class, List.class, String.class},
                request,
                List.of(scene),
                "ep-20260512233524-85r4g");

        assertThat(scenes).hasSize(2);
        assertThat(scenes).extracting(CarSalesVideoDTO.Scene::getDuration).containsExactly(15, 15);
        assertThat(scenes).extracting(CarSalesVideoDTO.Scene::getSegmentIndex).containsExactly(1, 2);
    }

    @Test
    void seatSpaceScenePrefersSeatReferencesOverDashboardForSeedance15() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setHostAppearanceEnabled(false);
        request.setAssetRoleBindings(List.of(
                imageBinding("https://cdn.test/dashboard.jpg", "car_interior_dashboard", "\u4e2d\u63a7\u53f0"),
                imageBinding("https://cdn.test/front-seat.jpg", "car_interior_front_seat", "\u524d\u6392"),
                imageBinding("https://cdn.test/back-seat.jpg", "car_interior_back_seat", "\u540e\u6392")
        ));

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setTitle("\u5ea7\u6905\u7a7a\u95f4");
        scene.setVisualPrompt("\u5c55\u793a\u5ea7\u6905\u3001\u540e\u6392\u817f\u90e8\u7a7a\u95f4\u3001\u50a8\u7269\u548c\u4e58\u5750\u8212\u9002\u6027");

        Object selection = invokeResolveSceneImageSelection(request, scene, 4);

        assertThat(selectionRoles(selection)).startsWith("car_interior_front_seat");
    }

    @Test
    void lightSceneFallsBackToFrontReferenceWhenSpecificLightImageIsMissing() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setHostAppearanceEnabled(false);
        request.setAssetRoleBindings(List.of(
                imageBinding("https://cdn.test/wheel.jpg", "car_detail_wheel", "\u8f6e\u6bc2"),
                imageBinding("https://cdn.test/front.jpg", "car_exterior_front", "\u6b63\u9762"),
                imageBinding("https://cdn.test/side.jpg", "car_exterior_side", "\u4fa7\u9762")
        ));

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setTitle("\u8f66\u5934\u706f\u5149");
        scene.setVisualPrompt("\u56f4\u7ed5\u8f66\u5934\u3001\u706f\u7ec4\u3001\u524d\u8138\u548c\u8f66\u8eab\u9ad8\u5149\u505a\u8fd1\u666f\u5c55\u793a");

        Object selection = invokeResolveSceneImageSelection(request, scene, 2);

        assertThat(selectionRoles(selection)).startsWith("car_exterior_front");
    }

    @Test
    void strictStoryboardSanitizerRemovesAudioAndSubtitleInstructions() throws Exception {
        Object sanitized = invoke("sanitizeStoryboardText",
                new Class<?>[]{String.class, boolean.class, boolean.class},
                """
                        镜头意图 展示车辆外观与车身线条。
                        内容主导：视频模型按口播文案直接生成画面和原生音频。
                        字幕只在成片拼接后处理，优先按最终口播文案烧录。
                        口播严格使用已传入文案。
                        """,
                true,
                true);

        Method textMethod = sanitized.getClass().getDeclaredMethod("text");
        textMethod.setAccessible(true);
        String text = (String) textMethod.invoke(sanitized);

        assertThat(text)
                .contains("展示车辆外观")
                .doesNotContain("原生音频", "字幕", "口播文案", "口播严格");
    }

    @Test
    void hostModelNativeMultiSceneKeepsNativeAudioForLipSync() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("model_native");
        request.setHostAppearanceEnabled(true);
        request.setFinalVoiceText("Hello from the dealership. Let's take a closer look.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        invoke("enforceStrictVoiceConsistency", new Class<?>[]{CarSalesVideoDTO.class, List.class}, request,
                List.of(scene(1), scene(2)));

        assertThat(request.getAudioMode()).isEqualTo("model_native");
        assertThat(request.getVoicePolicy()).isEqualTo("model_native");
    }

    @Test
    void missingAudioModeDefaultsToSilentVehicleOnlyGeneration() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setBrandModel("Jetour G700");
        request.setSellingPoints("front lighting, cabin details");
        request.setCallToAction("book a test drive");
        request.setHostAppearanceEnabled(false);

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(request.getAudioMode()).isEqualTo("none");
        assertThat(request.getVoicePolicy()).isEqualTo("none");
        assertThat(request.getAudioUrl()).isNull();
        assertThat((Boolean) invoke("shouldGenerateNativeAudio",
                new Class<?>[]{CarSalesVideoDTO.class}, request)).isFalse();
        assertThat((Boolean) invoke("shouldUseFinalAudio",
                new Class<?>[]{CarSalesVideoDTO.class}, request)).isFalse();
        assertThat((Boolean) invoke("hasFinalNarrationAudio",
                new Class<?>[]{CarSalesVideoDTO.class}, request)).isFalse();
    }

    @Test
    void implicitAutoVoiceoverFromAutoTextSourceIsSilencedAndCleared() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("auto_tts");
        request.setVoicePolicy("auto_tts");
        request.setVoiceTextSource("auto");
        request.setFinalVoiceText("Auto-filled default narration that the user did not provide.");
        CarSalesVideoDTO.Scene scene = scene(1);
        request.setScenes(List.of(scene));

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(request.getAudioMode()).isEqualTo("none");
        assertThat(request.getVoicePolicy()).isEqualTo("none");
        assertThat(request.getFinalVoiceText()).isNull();
        assertThat(scene.getVoiceText()).isNull();
        assertThat((Boolean) invoke("hasFinalNarrationAudio",
                new Class<?>[]{CarSalesVideoDTO.class}, request)).isFalse();
    }

    @Test
    void manualAutoTtsRequestStillEnablesGeneratedVoiceover() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("auto_tts");
        request.setVoicePolicy("auto_tts");
        request.setVoiceTextSource("manual");
        request.setStrictVoiceText(true);
        request.setFinalVoiceText("A short user-provided walkaround narration.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(request.getAudioMode()).isEqualTo("auto_tts");
        assertThat(request.getVoicePolicy()).isEqualTo("auto_tts");
        assertThat(request.getFinalVoiceText()).isEqualTo("A short user-provided walkaround narration.");
    }

    @Test
    void explicitModelNativeVoicePolicyStillEnablesNativeAudioWhenAudioModeIsMissing() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setVoicePolicy("model_native");
        request.setVoiceTextSource("manual");
        request.setStrictVoiceText(true);
        request.setFinalVoiceText("Show the vehicle with a short narrated walkaround.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(request.getAudioMode()).isEqualTo("model_native");
        assertThat(request.getVoicePolicy()).isEqualTo("model_native");
    }

    @Test
    void nativeAudioCountsAsFinalNarrationForSubtitleRecognition() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("model_native");
        request.setFinalVoiceText("A natural English narration line.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean hasAudio = (Boolean) invoke("hasFinalNarrationAudio", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(hasAudio).isTrue();
    }

    @Test
    void modelNativeAudioUsesRecognitionTextInsteadOfTrustedScriptReplacement() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("model_native");
        request.setVoicePolicy("model_native");
        request.setSubtitleMode("auto");
        request.setSubtitle("自动生成");
        request.setFinalVoiceText("The intended script may not match the model generated speech exactly.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean shouldRecognize = (Boolean) invoke("shouldUseAudioRecognitionSubtitleTiming",
                new Class<?>[]{CarSalesVideoDTO.class, List.class}, request, List.of(scene(1)));
        String trustedScriptText = (String) invoke("trustedScriptSubtitleTextForAudioTiming",
                new Class<?>[]{CarSalesVideoDTO.class}, request);
        String fallbackText = (String) invoke("resolveBurnedSubtitleText",
                new Class<?>[]{CarSalesVideoDTO.class, List.class}, request, List.of(scene(1)));

        assertThat(shouldRecognize).isTrue();
        assertThat(trustedScriptText).isNull();
        assertThat(fallbackText).isNull();
    }

    @Test
    void uploadedVoiceAudioUsesRecognitionTextInsteadOfTrustedScriptReplacement() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("post_mix");
        request.setAudioUrl("https://cdn.test/user-voice.mp3");
        request.setVoicePolicy("user_audio");
        request.setSubtitleMode("auto");
        request.setSubtitle("自动生成");
        request.setFinalVoiceText("This script may differ from the uploaded recording.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean shouldRecognize = (Boolean) invoke("shouldUseAudioRecognitionSubtitleTiming",
                new Class<?>[]{CarSalesVideoDTO.class, List.class}, request, List.of(scene(1)));
        String trustedScriptText = (String) invoke("trustedScriptSubtitleTextForAudioTiming",
                new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(shouldRecognize).isTrue();
        assertThat(trustedScriptText).isNull();
    }

    @Test
    void autoTtsAudioKeepsTrustedScriptTextForSubtitleTimingAlignment() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("post_mix");
        request.setAudioUrl("https://cdn.test/generated-voice.mp3");
        request.setGeneratedVoiceUrl("https://cdn.test/generated-voice.mp3");
        request.setVoicePolicy("auto_tts");
        request.setSubtitleMode("auto");
        request.setSubtitle("自动生成");
        request.setFinalVoiceText("A generated narration should keep this exact subtitle text.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        String trustedScriptText = (String) invoke("trustedScriptSubtitleTextForAudioTiming",
                new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(trustedScriptText).isEqualTo("A generated narration should keep this exact subtitle text.");
    }

    @Test
    void uploadedVoiceAudioUsesAudioMasterWhenSyncStrategyIsAuto() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("post_mix");
        request.setAudioUrl("https://cdn.test/user-voice.mp3");
        request.setVoicePolicy("user_audio");
        request.setSyncStrategy("auto");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean audioMaster = (Boolean) invoke("shouldUseAudioMasterSync",
                new Class<?>[]{CarSalesVideoDTO.class, String.class}, request, request.getAudioUrl());

        assertThat(audioMaster).isTrue();
    }

    @Test
    void autoTtsAudioUsesAudioMasterWhenSyncStrategyIsAuto() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("post_mix");
        request.setAudioUrl("https://cdn.test/generated-voice.mp3");
        request.setGeneratedVoiceUrl("https://cdn.test/generated-voice.mp3");
        request.setVoicePolicy("auto_tts");
        request.setSyncStrategy("auto");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean audioMaster = (Boolean) invoke("shouldUseAudioMasterSync",
                new Class<?>[]{CarSalesVideoDTO.class, String.class}, request, request.getAudioUrl());

        assertThat(audioMaster).isTrue();
    }

    @Test
    void autoTtsQuotaErrorFallsBackToModelNativeVoiceover() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("auto_tts");
        request.setVoicePolicy("auto_tts");
        request.setSyncStrategy("audio_master");
        request.setNativeVoiceLanguage("en-US");
        request.setFinalVoiceText("Show this SUV with a confident sales narration.");

        invoke("fallbackAutoTtsToModelNativeVoiceover",
                new Class<?>[]{CarSalesVideoDTO.class, List.class, com.huashuo.common.exception.BusinessException.class},
                request,
                List.of(scene(1), scene(2)),
                new com.huashuo.common.exception.BusinessException(50100,
                        "Volcengine TTS submit: quota exceeded for types: text_words_lifetime"));

        assertThat(request.getAudioMode()).isEqualTo("model_native");
        assertThat(request.getVoicePolicy()).isEqualTo("model_native");
        assertThat(request.getAudioUrl()).isNull();
        assertThat(request.getSyncStrategy()).isEqualTo("auto");
    }

    @Test
    void detectsVolcengineTextWordQuotaAsAutoTtsQuotaLimit() {
        Boolean quotaLimit = (Boolean) invoke("isAutoTtsQuotaLimitException",
                new Class<?>[]{Throwable.class},
                new com.huashuo.common.exception.BusinessException(50100,
                        "Volcengine TTS submit: quota exceeded for types: text_words_lifetime"));

        assertThat(quotaLimit).isTrue();
    }

    @Test
    void autoSubtitleDoesNotRecognizeBgmWhenNoNarrationAudioExists() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("none");
        request.setVoicePolicy("none");
        request.setBgmUrl("https://cdn.test/bgm.mp3");
        request.setSubtitleMode("auto");
        request.setSubtitle("自动生成");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean shouldRecognize = (Boolean) invoke("shouldUseAudioRecognitionSubtitleTiming",
                new Class<?>[]{CarSalesVideoDTO.class, List.class}, request, List.of());
        Boolean shouldFallback = (Boolean) invoke("shouldFallbackToAudioRecognitionSubtitle",
                new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(shouldRecognize).isFalse();
        assertThat(shouldFallback).isFalse();
    }

    @Test
    void subtitleDefaultsUseYaheiAndReadableFontSize() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setSubtitleMode("auto");
        request.setSubtitle("自动生成");

        Object layout = invoke("subtitleLayout", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Method assFontSize = layout.getClass().getDeclaredMethod("assFontSize");
        Method srtFontSize = layout.getClass().getDeclaredMethod("srtFontSize");
        assFontSize.setAccessible(true);
        srtFontSize.setAccessible(true);

        Class<?> subtitleFontClass = Class.forName(VideoServiceImpl.class.getName() + "$SubtitleFont");
        String fontName = (String) invoke("subtitleFontNameForStyle",
                new Class<?>[]{CarSalesVideoDTO.class, subtitleFontClass}, request, null);

        assertThat(assFontSize.invoke(layout)).isEqualTo(20);
        assertThat(srtFontSize.invoke(layout)).isEqualTo(10);
        assertThat(fontName).isEqualTo("Microsoft YaHei");

        CarSalesVideoDTO.TextOverlay overlay = new CarSalesVideoDTO.TextOverlay();
        overlay.setFontSize(72);
        request.setSubtitleOverlay(overlay);
        layout = invoke("subtitleLayout", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(assFontSize.invoke(layout)).isEqualTo(24);
        assertThat(srtFontSize.invoke(layout)).isEqualTo(12);

        overlay.setFontSize(1);
        layout = invoke("subtitleLayout", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(assFontSize.invoke(layout)).isEqualTo(16);
        assertThat(srtFontSize.invoke(layout)).isEqualTo(9);
    }

    @Test
    void verticalHeadlineOverlayUsesSafeFontAndWrapsBeforeBurning() throws Exception {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAspectRatio("9:16");

        CarSalesVideoDTO.TextOverlay overlay = new CarSalesVideoDTO.TextOverlay();
        overlay.setEnabled(true);
        overlay.setFontSize(156);
        overlay.setPosition("bottom");
        request.setHeadlineOverlay(overlay);

        int fontSize = ((Number) invoke("normalizeHeadlineFontSize",
                new Class<?>[]{Integer.class, CarSalesVideoDTO.class}, overlay.getFontSize(), request)).intValue();
        assertThat(fontSize).isEqualTo(72);

        String wrapped = (String) invoke("wrapHeadlineOverlayText",
                new Class<?>[]{String.class, int.class, CarSalesVideoDTO.class},
                "侧颜自带超强气场宽体轮眉硬派越野范", fontSize, request);
        assertThat(wrapped).contains("\n");
        for (String line : wrapped.split("\\n")) {
            int weight = ((Number) invoke("subtitleDisplayWeight", new Class<?>[]{String.class}, line)).intValue();
            assertThat(weight).isLessThanOrEqualTo(24);
        }

        String filter = (String) invoke("buildHeadlineDrawtextFilter",
                new Class<?>[]{Path.class, CarSalesVideoDTO.class, CarSalesVideoDTO.TextOverlay.class, int.class},
                Path.of("headline.txt"), request, overlay, fontSize);
        assertThat(filter).contains(":fix_bounds=1", ":fontsize=72");
    }

    @Test
    void modelNativeVoiceTextCollapsesRepeatedSceneLinesBeforeSegmentSplit() {
        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) invoke("splitVoiceTextForSegments",
                new Class<?>[]{String.class, int.class},
                "First, welcome to the car.\nFirst, welcome to the car.\nNow look at the premium cabin.\nFinally, book a test drive.",
                3);

        assertThat(chunks).containsExactly(
                "First, welcome to the car.",
                "Now look at the premium cabin.",
                "Finally, book a test drive.");
    }

    @Test
    void applyVoiceTextToScenesPreservesStoryboardNarrationBindings() {
        List<CarSalesVideoDTO.Scene> scenes = List.of(scene(1), scene(2));
        scenes.get(0).setVoiceText("Storyboard narration one.");
        scenes.get(1).setVoiceText("Storyboard narration two.");

        invoke("applyVoiceTextToScenes", new Class<?>[]{List.class, String.class},
                scenes,
                "A different full script sentence. Another different full script sentence.");

        assertThat(scenes).extracting(CarSalesVideoDTO.Scene::getVoiceText)
                .containsExactly("Storyboard narration one.", "Storyboard narration two.");
    }

    @Test
    void autoSubtitleMergesShortAsrCuesIntoSentenceSizedCaptions() {
        @SuppressWarnings("unchecked")
        List<Object> cues = (List<Object>) invoke("parseSrtCues", new Class<?>[]{String.class}, """
                1
                00:00:00,000 --> 00:00:00,400
                to drive?

                2
                00:00:00,400 --> 00:00:01,000
                This is

                3
                00:00:01,000 --> 00:00:01,700
                the Geely Binyue.

                4
                00:00:01,700 --> 00:00:02,300
                Sharp exterior

                5
                00:00:02,300 --> 00:00:03,000
                with a confident stance.
                """);

        @SuppressWarnings("unchecked")
        List<Object> merged = (List<Object>) invoke("mergeSrtCuesBySentence",
                new Class<?>[]{List.class}, cues);
        String formatted = (String) invoke("formatSrtCues", new Class<?>[]{List.class}, merged);

        assertThat(merged).hasSize(3);
        assertThat(formatted).contains("to drive?\n\n2\n00:00:00,400 --> 00:00:01,700\nThis is the Geely Binyue.");
        assertThat(formatted).contains("3\n00:00:01,700 --> 00:00:03,000\nSharp exterior with a confident stance.");
    }

    private CarSalesVideoDTO buildQuickCarSalesRequest(QuickRenderRequest request, List<Object> materials)
            throws Exception {
        QuickRenderServiceImpl quickRenderService = new QuickRenderServiceImpl(
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                "ffmpeg"
        );
        Method method = QuickRenderServiceImpl.class.getDeclaredMethod("buildCarSalesRequest",
                QuickRenderRequest.class, List.class, OptionalLong.class);
        method.setAccessible(true);
        return (CarSalesVideoDTO) method.invoke(quickRenderService, request, materials, OptionalLong.empty());
    }

    private Object quickMaterial(AssetItem asset, String role, String text) throws Exception {
        Class<?> materialClass = Class.forName(QuickRenderServiceImpl.class.getName() + "$Material");
        Constructor<?> constructor = materialClass.getDeclaredConstructor(AssetItem.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(asset, role, text);
    }

    private AssetItem quickAsset(Long id, String type, String mimeType, String fileName, String fileUrl) {
        return new AssetItem(
                id,
                1L,
                1L,
                null,
                null,
                type,
                "raw",
                "private",
                "ready",
                null,
                fileName,
                null,
                fileUrl,
                null,
                mimeType,
                null,
                "test",
                null,
                null,
                null,
                null
        );
    }

    private QuickRenderRequest.GeneratedStoryboardShot quickShot(int index, String visual, String narration,
                                                                 int duration) {
        QuickRenderRequest.GeneratedStoryboardShot shot = new QuickRenderRequest.GeneratedStoryboardShot();
        shot.setIndex(index);
        shot.setVisual(visual);
        shot.setNarration(narration);
        shot.setDuration(duration);
        return shot;
    }

    private CarSalesVideoDTO.Scene scene(int index) {
        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setSegmentIndex(index);
        scene.setTitle("scene " + index);
        scene.setVisualPrompt("show the car");
        scene.setVoiceText("Line " + index);
        scene.setDuration(8);
        return scene;
    }

    private Object sceneImageSelection(List<String> urls, List<String> roles, List<String> labels) throws Exception {
        Class<?> selectionClass = Class.forName(VideoServiceImpl.class.getName() + "$SceneImageSelection");
        Constructor<?> constructor = selectionClass.getDeclaredConstructor(
                List.class, List.class, List.class, String.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(urls, roles, labels, "test", roles);
    }

    private CarSalesVideoDTO.AssetRoleBinding imageBinding(String url, String role, String label) {
        CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
        binding.setUrl(url);
        binding.setAssetRole(role);
        binding.setAssetType("IMAGE");
        binding.setLabel(label);
        return binding;
    }

    private Object invokeResolveSceneImageSelection(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene,
                                                   int sceneIndex) throws Exception {
        Method method = VideoServiceImpl.class.getDeclaredMethod("resolveSceneImageSelection",
                CarSalesVideoDTO.class,
                CarSalesVideoDTO.Scene.class,
                int.class,
                String.class);
        method.setAccessible(true);
        return method.invoke(service, request, scene, sceneIndex, "doubao-seedance-1-5-pro-251215");
    }

    @SuppressWarnings("unchecked")
    private List<String> selectionRoles(Object selection) throws Exception {
        Method rolesMethod = selection.getClass().getDeclaredMethod("roles");
        rolesMethod.setAccessible(true);
        return (List<String>) rolesMethod.invoke(selection);
    }

    private Object invokeBuildPrompt(CarSalesVideoDTO request, CarSalesVideoDTO.Scene scene,
                                     Object imageSelection) throws Exception {
        Method method = VideoServiceImpl.class.getDeclaredMethod("buildCarSalesScenePrompt",
                CarSalesVideoDTO.class,
                CarSalesVideoDTO.Scene.class,
                int.class,
                int.class,
                String.class,
                imageSelection.getClass());
        method.setAccessible(true);
        return method.invoke(service, request, scene, 1, 2, "ep-20260512233524-85r4g", imageSelection);
    }

    private Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = VideoServiceImpl.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(service, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
