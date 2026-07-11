package com.huashuo.common.video.longform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartSegmentPlannerTest {

    private final SmartSegmentPlanner planner = new SmartSegmentPlanner();

    @Test
    void plansPetStoryAtNaturalSceneBoundaries() {
        List<SegmentPlan> segments = planner.plan(List.of(
                scene("scene_01", 0, 5),
                scene("scene_02", 5, 10),
                scene("scene_03", 10, 16),
                scene("scene_04", 16, 23),
                scene("scene_05", 23, 30)
        ), 30, 4, 15);

        assertThat(segments).hasSize(3);
        assertThat(segments).extracting(SegmentPlan::globalStartSeconds)
                .containsExactly(0, 10, 23);
        assertThat(segments).extracting(SegmentPlan::globalEndSeconds)
                .containsExactly(10, 23, 30);
        assertThat(segments).extracting(SegmentPlan::durationSeconds)
                .containsExactly(10, 13, 7);
        assertThat(segments.get(1).scenes()).extracting(StoryboardScene::sceneId)
                .containsExactly("scene_03", "scene_04");
    }

    @Test
    void neverCreatesProviderSegmentLongerThanLimit() {
        List<SegmentPlan> segments = planner.plan(List.of(
                scene("scene_01", 0, 8),
                scene("scene_02", 8, 14),
                scene("scene_03", 14, 21),
                scene("scene_04", 21, 30)
        ), 30, 4, 15);

        assertThat(segments).allSatisfy(segment ->
                assertThat(segment.durationSeconds()).isBetween(4, 15));
    }

    @Test
    void balancesWhenNaturalLastSceneWouldBeTooShort() {
        List<SegmentPlan> segments = planner.plan(List.of(
                scene("scene_01", 0, 8),
                scene("scene_02", 8, 16),
                scene("scene_03", 16, 27),
                scene("scene_04", 27, 30)
        ), 30, 4, 15);

        assertThat(segments).hasSize(3);
        assertThat(segments).extracting(SegmentPlan::durationSeconds)
                .containsExactly(8, 8, 14)
                .doesNotContain(3);
    }

    private static StoryboardScene scene(String id, int start, int end) {
        return new StoryboardScene(
                id,
                start,
                end,
                id + " visual",
                id + " action",
                "stable shot",
                List.of(new DialogueCue("speaker", "line", "subtitle", start, end)),
                List.of()
        );
    }
}
