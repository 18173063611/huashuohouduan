package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PetLongVideoCarPollutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetLongVideoGenerationOrchestrator orchestrator = new PetLongVideoGenerationOrchestrator(
            objectMapper,
            new PetVideoProperties(),
            mock(PetLongVideoManifestService.class),
            mock(PetLongVideoSegmentTaskService.class),
            mock(PetLongVideoStitchService.class),
            mock(PetLongVideoResultAssetService.class)
    );

    @Test
    void rejectsCarBusinessTermsInPositivePrompt() throws Exception {
        ObjectNode manifest = PetLongVideoTestFixtures.manifest(objectMapper);
        ((ObjectNode) manifest.withArray("segments").get(0).get("providerPayload"))
                .put("prompt", "宠物剧情短片，但内容是汽车销售和车型介绍");

        assertThatThrownBy(() -> orchestrator.validateManifest(manifest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PET_LONG_VIDEO_CAR_POLLUTION");
    }

    @Test
    void allowsCarBanOnlyInNegativePrompt() throws Exception {
        ObjectNode manifest = PetLongVideoTestFixtures.manifest(objectMapper);
        ((ObjectNode) manifest.withArray("segments").get(0).get("providerPayload"))
                .put("prompt", "pet story dog cat owner dialogue")
                .put("negativePrompt", "不要出现汽车、汽车展厅、车型或试驾");

        orchestrator.validateManifest(manifest);
    }

    @Test
    void rejectsCarBusinessDomainInsidePetSegmentPayload() throws Exception {
        ObjectNode manifest = PetLongVideoTestFixtures.manifest(objectMapper);
        ((ObjectNode) manifest.withArray("segments").get(0).get("providerPayload"))
                .put("businessDomain", "car");

        assertThatThrownBy(() -> orchestrator.validateManifest(manifest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("segment businessDomain must be pet");
    }
}
