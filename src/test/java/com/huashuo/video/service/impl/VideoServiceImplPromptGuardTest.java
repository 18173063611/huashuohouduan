package com.huashuo.video.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(prompt.length()).isLessThanOrEqualTo(700);
        assertThat(prompt)
                .contains("\u9886\u514b 06",
                        "\u5185\u9970\u5ea7\u8231",
                        "\u955c\u5934",
                        "\u53c2\u8003\u56fe",
                        "\u97f3\u9891",
                        "\u753b\u9762\u7528\u9014",
                        "\u4ea7\u54c1\u7ea7\u8f66\u8f86\u5c55\u793a\u753b\u9762",
                        "\u540e\u671f\u5408\u6210")
                .doesNotContain("\u753b\u9762\u6587\u5b57\u786c\u6027\u7981\u4ee4",
                        "\u6700\u9ad8\u4f18\u5148\u7ea7\u4eba\u7269\u7981\u4ee4",
                        "\u80cc\u666f\u97f3\u4e50\u786c\u6027\u7981\u4ee4",
                        "\u7edd\u5bf9\u4e0d\u5f97\u51fa\u73b0",
                        "\u4e0d\u8981",
                        "\u4e0d\u5f97",
                        "\u7981\u6b62",
                        "\u7edd\u5bf9",
                        "\u65e0\u5b57\u5e55",
                        "\u65e0\u4eba\u50cf",
                        "\u4eba\u7269\u5904\u7406",
                        "\u624b\u90e8",
                        "\u8def\u4eba");
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
        assertThat(srtFontSize.invoke(layout)).isEqualTo(20);
        assertThat(fontName).isEqualTo("Microsoft YaHei");

        CarSalesVideoDTO.TextOverlay overlay = new CarSalesVideoDTO.TextOverlay();
        overlay.setFontSize(72);
        request.setSubtitleOverlay(overlay);
        layout = invoke("subtitleLayout", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(assFontSize.invoke(layout)).isEqualTo(72);
        assertThat(srtFontSize.invoke(layout)).isEqualTo(72);

        overlay.setFontSize(1);
        layout = invoke("subtitleLayout", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(assFontSize.invoke(layout)).isEqualTo(1);
        assertThat(srtFontSize.invoke(layout)).isEqualTo(1);
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
