package com.huashuo.video.service.impl;

import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CarSalesScenePlannerTest {

    private static final String SEEDANCE_2 = "ep-20260512233524-85r4g";

    @Test
    void compactsAdjacentShortScenesInsideSeedance2Limit() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "front", 4, "Smooth opening", "https://cdn.test/car.jpg"),
                scene(2, "interior", 5, "spacious cabin", "https://cdn.test/interior.jpg"),
                scene(3, "details", 6, "premium trim", "https://cdn.test/car.jpg")
        ), SEEDANCE_2);

        assertThat(result).hasSize(1);
        CarSalesVideoDTO.Scene merged = result.get(0);
        assertThat(merged.getSegmentIndex()).isEqualTo(1);
        assertThat(merged.getDuration()).isEqualTo(15);
        assertThat(merged.getVisualPrompt()).contains("front", "interior", "details");
        assertThat(merged.getVoiceText()).isEqualTo("Smooth opening spacious cabin premium trim");
        assertThat(merged.getImageUrls()).containsExactly(
                "https://cdn.test/car.jpg",
                "https://cdn.test/interior.jpg"
        );
    }

    @Test
    void splitsOnlyWhenCombinedDurationExceedsSeedance2Limit() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "front", 8, "first", "https://cdn.test/1.jpg"),
                scene(2, "side", 8, "second", "https://cdn.test/2.jpg")
        ), SEEDANCE_2);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CarSalesVideoDTO.Scene::getDuration)
                .containsExactly(8, 8);
        assertThat(result).extracting(CarSalesVideoDTO.Scene::getSegmentIndex)
                .containsExactly(1, 2);
    }

    @Test
    void usesTwelveSecondLimitForOtherModels() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "front", 6, "first", "https://cdn.test/1.jpg"),
                scene(2, "side", 6, "second", "https://cdn.test/2.jpg"),
                scene(3, "tail", 4, "third", "https://cdn.test/3.jpg")
        ), "legacy-model");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CarSalesVideoDTO.Scene::getDuration)
                .containsExactly(12, 4);
    }

    private static CarSalesVideoDTO.Scene scene(int index, String title, int duration, String voiceText, String imageUrl) {
        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setSegmentIndex(index);
        scene.setTitle(title);
        scene.setVisualPrompt(title + " visual");
        scene.setPrompt(scene.getVisualPrompt());
        scene.setVoiceText(voiceText);
        scene.setDuration(duration);
        scene.setImageUrls(List.of(imageUrl));
        scene.setReferenceImage(imageUrl);
        return scene;
    }
}
