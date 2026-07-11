package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PetLongVideoGenerationOrchestratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetVideoProperties properties = new PetVideoProperties();
    private final PetLongVideoManifestService manifestService = mock(PetLongVideoManifestService.class);
    private final PetLongVideoSegmentTaskService segmentTaskService = mock(PetLongVideoSegmentTaskService.class);
    private final PetLongVideoStitchService stitchService = mock(PetLongVideoStitchService.class);
    private final PetLongVideoResultAssetService resultAssetService = mock(PetLongVideoResultAssetService.class);
    private final PetLongVideoGenerationOrchestrator orchestrator = new PetLongVideoGenerationOrchestrator(
            objectMapper,
            properties,
            manifestService,
            segmentTaskService,
            stitchService,
            resultAssetService
    );

    @Test
    void dryRunPersistsManifestAndReturnsExecutionPlans() throws Exception {
        ObjectNode manifest = manifest();
        when(stitchService.buildDryRunStitchManifest(any())).thenAnswer(invocation -> {
            ObjectNode stitch = objectMapper.createObjectNode();
            stitch.put("outputSingleVideo", true);
            stitch.put("readyForRealStitch", false);
            return stitch;
        });
        when(resultAssetService.buildResultAssetPlan(any())).thenAnswer(invocation -> {
            ObjectNode plan = objectMapper.createObjectNode();
            plan.put("businessDomain", "pet");
            plan.put("assetType", "PET_LONG_VIDEO_RESULT");
            return plan;
        });
        doAnswer(invocation -> {
            ObjectNode node = invocation.getArgument(0);
            node.put("longVideoManifestId", 88L);
            return node;
        }).when(manifestService).ensurePersisted(any(), eq(7L));

        ObjectNode response = orchestrator.dryRun(manifest, 7L);

        assertThat(response.get("dryRun").asBoolean()).isTrue();
        assertThat(response.get("providerSubmitted").asBoolean()).isFalse();
        assertThat(response.get("taskCreated").asBoolean()).isFalse();
        assertThat(response.get("longVideoManifestId").asLong()).isEqualTo(88L);
        assertThat(response.get("segmentSubmitPlan")).hasSize(3);
        assertThat(response.get("pollPlan").get("pollExistingTasksOnly").asBoolean()).isTrue();
        verify(manifestService).ensurePersisted(any(), eq(7L));
    }

    @Test
    void submitRequiresProviderFlagAndExplicitConfirmation() {
        properties.setProviderSubmitEnabled(false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("longVideoManifestId", 88L);
        request.put("confirmRealSubmit", true);

        assertThatThrownBy(() -> orchestrator.submit(request, 7L, "trace"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PROVIDER_SUBMIT_DISABLED");
    }

    @Test
    void authorizedSubmitOnceEnablesProviderOnlyForOneCall() throws Exception {
        ObjectNode manifest = manifest();
        manifest.put("longVideoManifestId", 88L);
        manifest.put("dryRunPassed", true);
        when(manifestService.loadManifest(88L, 7L)).thenReturn(manifest);
        doAnswer(invocation -> {
            ObjectNode segment = invocation.getArgument(0);
            segment.put("generationTaskId", 100L + segment.get("segmentIndex").asLong());
            segment.put("providerTaskId", String.valueOf(100L + segment.get("segmentIndex").asLong()));
            segment.put("taskStatus", "submitted");
            return segment;
        }).when(segmentTaskService).submitSegment(any(), eq(7L), eq("trace"));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("longVideoManifestId", 88L);
        request.put("confirmRealSubmit", true);
        request.put("authorizationCode", "PET_LONG_VIDEO_2787_20260710_ONCE");

        ObjectNode response = orchestrator.authorizedSubmitOnce(request, 7L, "trace");

        assertThat(response.get("providerSubmitted").asBoolean()).isTrue();
        assertThat(response.get("taskCreated").asBoolean()).isTrue();
        assertThat(response.get("providerSubmitEnabledAfterFinally").asBoolean()).isFalse();
        assertThat(properties.isProviderSubmitEnabled()).isFalse();
        verify(segmentTaskService, times(3)).submitSegment(any(), eq(7L), eq("trace"));
    }

    @Test
    void authorizedSubmitOnceRejectsInvalidAuthorizationCode() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("longVideoManifestId", 88L);
        request.put("confirmRealSubmit", true);
        request.put("authorizationCode", "wrong");

        assertThatThrownBy(() -> orchestrator.authorizedSubmitOnce(request, 7L, "trace"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PET_LONG_VIDEO_AUTHORIZATION_CODE_INVALID");
        assertThat(properties.isProviderSubmitEnabled()).isFalse();
    }

    private ObjectNode manifest() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "compositionId":"2787",
                  "title":"living room snack meeting",
                  "businessDomain":"pet",
                  "templateType":"PET_STORY_LONG_VIDEO",
                  "totalDurationSeconds":30,
                  "aspectRatio":"9:16",
                  "estimatedCredits":840,
                  "audit":{"bannedAssetCheck":true,"carPollutionCheck":true},
                  "segments":[
                    {
                      "segmentIndex":1,
                      "globalStart":0,
                      "globalEnd":10,
                      "durationSeconds":10,
                      "idempotencyKey":"pet-long-video-2787-segment-1-v1",
                      "estimatedCredits":280,
                      "referenceAssetIds":["2713","2691","2786","2683"],
                      "localDialogues":[{"speakerId":"dog","text":"snack?","subtitle":"snack?","start":0,"end":5}],
                      "localSubtitles":[{"speakerId":"dog","text":"snack?","start":0,"end":5}],
                      "voiceMapping":{"dog":"voice_dog"},
                      "providerPayload":{"businessDomain":"pet","prompt":"pet story dog cat owner dialogue"}
                    },
                    {
                      "segmentIndex":2,
                      "globalStart":10,
                      "globalEnd":23,
                      "durationSeconds":13,
                      "idempotencyKey":"pet-long-video-2787-segment-2-v1",
                      "estimatedCredits":280,
                      "referenceAssetIds":["2713","2691","2786","2683"],
                      "localDialogues":[{"speakerId":"owner","text":"task","subtitle":"task","start":0,"end":6}],
                      "localSubtitles":[{"speakerId":"owner","text":"task","start":0,"end":6}],
                      "voiceMapping":{"owner":"voice_owner"},
                      "providerPayload":{"businessDomain":"pet","prompt":"pet story owner assigns a small task"}
                    },
                    {
                      "segmentIndex":3,
                      "globalStart":23,
                      "globalEnd":30,
                      "durationSeconds":7,
                      "idempotencyKey":"pet-long-video-2787-segment-3-v1",
                      "estimatedCredits":280,
                      "referenceAssetIds":["2713","2691","2786","2683"],
                      "localDialogues":[{"speakerId":"cat","text":"budget","subtitle":"budget","start":0,"end":4}],
                      "localSubtitles":[{"speakerId":"cat","text":"budget","start":0,"end":4}],
                      "voiceMapping":{"cat":"voice_cat"},
                      "providerPayload":{"businessDomain":"pet","prompt":"pet story warm ending"}
                    }
                  ]
                }
                """);
    }
}
