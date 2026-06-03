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
    void nativeAudioCountsAsFinalNarrationForSubtitleRecognition() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("model_native");
        request.setFinalVoiceText("A natural English narration line.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        Boolean hasAudio = (Boolean) invoke("hasFinalNarrationAudio", new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(hasAudio).isTrue();
    }

    @Test
    void uploadedVoiceAudioUsesRecognitionTextInsteadOfCanonicalScriptReplacement() {
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
        String canonicalText = (String) invoke("canonicalSubtitleTextForAudioTiming",
                new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(shouldRecognize).isTrue();
        assertThat(canonicalText).isNull();
    }

    @Test
    void autoTtsAudioKeepsCanonicalTextForSubtitleTimingAlignment() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("post_mix");
        request.setAudioUrl("https://cdn.test/generated-voice.mp3");
        request.setGeneratedVoiceUrl("https://cdn.test/generated-voice.mp3");
        request.setVoicePolicy("auto_tts");
        request.setSubtitleMode("auto");
        request.setSubtitle("自动生成");
        request.setFinalVoiceText("A generated narration should keep this exact subtitle text.");

        invoke("normalizeCarSalesVoicePolicy", new Class<?>[]{CarSalesVideoDTO.class}, request);
        String canonicalText = (String) invoke("canonicalSubtitleTextForAudioTiming",
                new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(canonicalText).isEqualTo("A generated narration should keep this exact subtitle text.");
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
    void subtitleDefaultsUseYaheiAndTwentyPointSize() throws Exception {
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
