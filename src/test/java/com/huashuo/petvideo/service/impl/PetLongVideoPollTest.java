package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.petvideo.service.PetVideoService;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PetLongVideoPollTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetVideoService petVideoService = mock(PetVideoService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final PetLongVideoSegmentTaskService service =
            new PetLongVideoSegmentTaskService(petVideoService, taskService, objectMapper);

    @Test
    void mapsSucceededTaskBackToSegmentResult() throws Exception {
        ObjectNode segment = (ObjectNode) objectMapper.readTree("""
                {"segmentIndex":1,"generationTaskId":123,"taskStatus":"processing"}
                """);
        when(taskService.getTaskForViewer(eq(123L), eq(OptionalLong.of(7L))))
                .thenReturn(task(TaskStatusCode.SUCCESS, "{\"videoUrl\":\"https://cdn.example.com/segment-1.mp4\"}", null));

        service.pollSegment(segment, 7L);

        assertThat(segment.get("taskStatus").asText()).isEqualTo("succeeded");
        assertThat(segment.get("resultUrl").asText()).isEqualTo("https://cdn.example.com/segment-1.mp4");
        assertThat(segment.get("outputAssetId").asLong()).isEqualTo(900L);
        assertThat(segment.get("actualChargedCredits").asLong()).isEqualTo(280L);
    }

    @Test
    void mapsFailedTaskWithoutCreatingNewTask() throws Exception {
        ObjectNode segment = (ObjectNode) objectMapper.readTree("""
                {"segmentIndex":2,"generationTaskId":124,"taskStatus":"processing"}
                """);
        when(taskService.getTaskForViewer(eq(124L), eq(OptionalLong.of(7L))))
                .thenReturn(task(TaskStatusCode.FAILED, null, "provider failed"));

        service.pollSegment(segment, 7L);

        assertThat(segment.get("taskStatus").asText()).isEqualTo("failed");
        assertThat(segment.get("errorMessage").asText()).isEqualTo("provider failed");
    }

    private TaskItem task(String status, String outputJson, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskItem(
                status.equals(TaskStatusCode.FAILED) ? 124L : 123L,
                null,
                7L,
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                "seedance",
                "seedance",
                "TASK",
                null,
                null,
                280L,
                280L,
                "SETTLED",
                280L,
                55L,
                "segment",
                status,
                TaskStatusCode.SUCCESS.equals(status) ? 100 : 60,
                TaskStatusCode.SUCCESS.equals(status) ? 900L : null,
                TaskStatusCode.FAILED.equals(status) ? "PROVIDER_FAILED" : null,
                errorMessage,
                0,
                false,
                "{}",
                outputJson,
                "trace",
                now,
                now,
                now,
                TaskStatusCode.SUCCESS.equals(status) ? now : null
        );
    }
}
