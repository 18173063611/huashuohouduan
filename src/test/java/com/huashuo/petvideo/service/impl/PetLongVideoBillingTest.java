package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.petvideo.config.PetVideoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PetLongVideoBillingTest {

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
    void dryRunReportsBillingSafetyWithoutChargingCredits() throws Exception {
        when(stitchService.buildDryRunStitchManifest(any())).thenReturn(objectMapper.createObjectNode()
                .put("outputSingleVideo", true)
                .put("readyForRealStitch", false));
        when(resultAssetService.buildResultAssetPlan(any())).thenReturn(objectMapper.createObjectNode()
                .put("businessDomain", "pet")
                .put("assetType", "PET_LONG_VIDEO_RESULT"));
        doAnswer(invocation -> {
            ObjectNode node = invocation.getArgument(0);
            node.put("longVideoManifestId", 2788L);
            return node;
        }).when(manifestService).ensurePersisted(any(), eq(7L));

        ObjectNode response = orchestrator.dryRun(PetLongVideoTestFixtures.manifest(objectMapper), 7L);
        ObjectNode billing = (ObjectNode) response.get("billingSafety");

        assertThat(billing.get("previewChargesCredits").asBoolean()).isFalse();
        assertThat(billing.get("estimateBeforeSubmit").asBoolean()).isTrue();
        assertThat(billing.get("realSubmit").asBoolean()).isFalse();
        assertThat(billing.get("estimatedCredits").asLong()).isEqualTo(840L);
        assertThat(billing.get("reservedCredits").asLong()).isZero();
        assertThat(billing.get("actualChargedCredits").asLong()).isZero();
        assertThat(billing.get("autoRetrySegmentSubmit").asBoolean()).isFalse();
        assertThat(billing.get("autoRegenerateOnStitchFailure").asBoolean()).isFalse();
    }
}
