package com.huashuo.video.service.impl;

import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CarSalesScenePlannerTest {

    private static final String SEEDANCE_2 = "ep-20260512233524-85r4g";
    private static final String SEEDANCE_2_PRO = "doubao-seedance-2-0-pro-250528";

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
    void splitsOversizedReferenceShotBeforeCompacting() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "long cabin move", 32,
                        "Open with the cabin. Show the family space. Close with the offer.",
                        "https://cdn.test/cabin.jpg")
        ), SEEDANCE_2);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(CarSalesVideoDTO.Scene::getDuration)
                .containsExactly(11, 11, 10);
        assertThat(result).extracting(CarSalesVideoDTO.Scene::getSegmentIndex)
                .containsExactly(1, 2, 3);
        assertThat(result.get(0).getVisualPrompt()).contains("Continuation 1/3");
        assertThat(result.get(1).getVisualPrompt()).contains("Continuation 2/3");
        assertThat(result.get(2).getVisualPrompt()).contains("Continuation 3/3");
    }

    @Test
    void recognizesSeedance2ProModelCode() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "front", 8, "first", "https://cdn.test/1.jpg"),
                scene(2, "side", 7, "second", "https://cdn.test/2.jpg")
        ), SEEDANCE_2_PRO);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDuration()).isEqualTo(15);
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

    @Test
    void keepsEnglishWordsAndSpacingWhenMergingVoiceText() {
        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(
                scene(1, "hook", 4, "Direct sales from Chinese factory,", "https://cdn.test/1.jpg"),
                scene(2, "proof", 4, "premium cars with fast delivery.", "https://cdn.test/2.jpg"),
                scene(3, "cta", 4, "Message us today", "https://cdn.test/3.jpg")
        ), SEEDANCE_2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVoiceText())
                .isEqualTo("Direct sales from Chinese factory, premium cars with fast delivery. Message us today");
    }

    @Test
    void compactsSameCarScenesEvenWhenCompareDimensionsDiffer() {
        CarSalesVideoDTO.Scene exterior = scene(1, "exterior", 6, "Look at the exterior.", "https://cdn.test/a.jpg");
        exterior.setCarPackageId("car-a");
        exterior.setCarIndex(1);
        exterior.setCompareDimension("外观质感");
        exterior.setShotPurpose("single_car_intro");
        CarSalesVideoDTO.Scene cabin = scene(2, "cabin", 6, "Now move into the cabin.", "https://cdn.test/b.jpg");
        cabin.setCarPackageId("car-a");
        cabin.setCarIndex(1);
        cabin.setCompareDimension("座舱空间");
        cabin.setShotPurpose("single_car_intro");

        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(exterior, cabin), SEEDANCE_2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDuration()).isEqualTo(12);
        assertThat(result.get(0).getCarPackageId()).isEqualTo("car-a");
    }

    @Test
    void splitsDifferentCarComparePurposes() {
        CarSalesVideoDTO.Scene intro = scene(1, "intro", 6, "Car A intro.", "https://cdn.test/a.jpg");
        intro.setCarPackageId("car-a");
        intro.setCarIndex(1);
        intro.setShotPurpose("single_car_intro");
        CarSalesVideoDTO.Scene compare = scene(2, "compare", 6, "Compare both cars.", "https://cdn.test/b.jpg");
        compare.setShotPurpose("dimension_compare");
        compare.setCompareDimension("配置对比");

        List<CarSalesVideoDTO.Scene> result = CarSalesScenePlanner.compactScenes(List.of(intro, compare), SEEDANCE_2);

        assertThat(result).hasSize(2);
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
