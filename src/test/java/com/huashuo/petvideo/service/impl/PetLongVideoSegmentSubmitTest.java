package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.service.PetVideoService;
import com.huashuo.task.service.TaskService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PetLongVideoSegmentSubmitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetVideoService petVideoService = mock(PetVideoService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final PetLongVideoSegmentTaskService service =
            new PetLongVideoSegmentTaskService(petVideoService, taskService, objectMapper);

    @Test
    void submitsSegmentOnceWithManifestIdempotencyKey() throws Exception {
        ObjectNode segment = segment();
        when(petVideoService.createTask(any(), eq(7L), eq("trace-1"), eq("pet-long-video-2787-segment-1-v1")))
                .thenReturn(new PetVideoTaskResponse("123", "segment", "queued", 0, null,
                        null, objectMapper.createObjectNode(), null, null, null, false, null, "now"));

        service.submitSegment(segment, 7L, "trace-1");

        assertThat(segment.get("generationTaskId").asLong()).isEqualTo(123L);
        assertThat(segment.get("providerTaskId").asText()).isEqualTo("123");
        assertThat(segment.get("taskStatus").asText()).isEqualTo("submitted");
        assertThat(segment.get("reservedCredits").asLong()).isEqualTo(280L);
        verify(petVideoService).createTask(any(), eq(7L), eq("trace-1"), eq("pet-long-video-2787-segment-1-v1"));
    }

    @Test
    void doesNotResubmitWhenTaskAlreadyExists() throws Exception {
        ObjectNode segment = segment();
        segment.put("generationTaskId", 123L);

        service.submitSegment(segment, 7L, "trace-1");

        verify(petVideoService, never()).createTask(any(), any(), any(), any());
        assertThat(segment.get("generationTaskId").asLong()).isEqualTo(123L);
    }

    @Test
    void draftForSubmitCarriesStoryboardDialogueAndPrompt() throws Exception {
        ObjectNode segment = segment();

        ObjectNode draft = service.draftForSubmit(segment);

        assertThat(draft.get("prompt").asText()).contains("宠物剧情长视频第1段", "snack budget");
        assertThat(draft.get("longVideoFullPrompt").asText()).contains("pet story segment 1");
        assertThat(draft.get("dialogueLines")).hasSize(1);
        assertThat(draft.get("dialogueLines").get(0).get("speakerRoleId").asText()).isEqualTo("dog");
        assertThat(draft.get("shots")).hasSize(3);
        assertThat(draft.get("shots").get(0).get("frameDescription").asText()).isEqualTo("living room");
        assertThat(draft.get("templateType").asText()).isEqualTo("PET_STORY_LONG_VIDEO_SEGMENT");
        assertThat(draft.get("longVideoSegmentIndex").asInt()).isEqualTo(1);
    }

    @Test
    void draftForSubmitKeepsPromptWithinValidatorLimit() throws Exception {
        ObjectNode segment = segment();
        segment.put("segmentPrompt", "宠物剧情长视频要求。".repeat(80));

        ObjectNode draft = service.draftForSubmit(segment);

        assertThat(draft.get("prompt").asText()).hasSizeLessThanOrEqualTo(500);
        assertThat(draft.get("longVideoFullPrompt").asText()).hasSizeGreaterThan(500);
    }

    private ObjectNode segment() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "segmentIndex":1,
                  "globalStart":0,
                  "globalEnd":10,
                  "durationSeconds":10,
                  "estimatedCredits":280,
                  "idempotencyKey":"pet-long-video-2787-segment-1-v1",
                  "segmentPrompt":"pet story segment 1 with dog cat and owner dialogue",
                  "localScenes":[{"sceneId":"scene_01","start":0,"end":5,"visual":"living room","action":"dog turns head","camera":"fixed"}],
                  "localDialogues":[{"speakerId":"dog","text":"snack budget?","subtitle":"dog: snack budget?","start":0,"end":5}],
                  "localSubtitles":[{"speakerId":"dog","text":"dog: snack budget?","start":0,"end":5}],
                  "voiceMapping":{"dog":"voice_dog"},
                  "subtitleConfig":{"enabled":true},
                  "providerPayload":{
                    "businessDomain":"pet",
                    "prompt":"pet story segment 1",
                    "diagnosticMetadata":{
                      "templateId":"pet-human-story-video",
                      "prompt":"old summary",
                      "durationSeconds":10,
                      "aspectRatio":"9:16",
                      "materials":[{"role":"main_pet","url":"https://cdn.example.com/dog.jpg"}]
                    }
                  }
                }
                """);
    }
}
