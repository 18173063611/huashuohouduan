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
