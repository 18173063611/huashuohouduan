package com.huashuo.video.service.impl;

import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CarLongVideoRegressionTest {

    @Test
    void carSalesScenePlannerSnapshotDoesNotContainPetFields() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "front", 4, "Smooth opening", "https://cdn.test/car.jpg"),
                scene(2, "interior", 5, "spacious cabin", "https://cdn.test/interior.jpg"),
                scene(3, "details", 6, "premium trim", "https://cdn.test/car.jpg")
        ), "ep-20260512233524-85r4g");

        assertThat(result).hasSize(1);
        CarSalesVideoDTO.Scene merged = result.get(0);
        assertThat(merged.getSegmentIndex()).isEqualTo(1);
        assertThat(merged.getDuration()).isEqualTo(15);
        assertThat(merged.getVisualPrompt()).contains("连续生成段落", "front", "interior", "details");
        assertThat(merged.getVisualPrompt()).doesNotContain("宠物", "豆包", "栗子", "human_avatar");
        assertThat(merged.getVoiceText()).isEqualTo("Smooth opening spacious cabin premium trim");
        assertThat(merged.getImageUrls()).containsExactly(
                "https://cdn.test/car.jpg",
                "https://cdn.test/interior.jpg"
        );
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
