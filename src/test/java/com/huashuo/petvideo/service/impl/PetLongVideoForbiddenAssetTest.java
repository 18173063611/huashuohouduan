package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PetLongVideoForbiddenAssetTest {

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
    void rejectsManifestWhenAuditMarksBannedAsset() throws Exception {
        ObjectNode manifest = PetLongVideoTestFixtures.manifest(objectMapper);
        ((ObjectNode) manifest.get("audit")).put("bannedAssetCheck", false);

        assertThatThrownBy(() -> orchestrator.validateManifest(manifest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("banned asset check failed");
    }

    @Test
    void rejectsExplicitForbiddenAssetMarkerAnywhereInManifest() throws Exception {
        ObjectNode manifest = PetLongVideoTestFixtures.manifest(objectMapper);
        ((ObjectNode) manifest.withArray("segments").get(0).get("providerPayload"))
                .put("referenceImage", "https://cdn.example.com/pet-cat-front-white-brown-face.jpg");

        assertThatThrownBy(() -> orchestrator.validateManifest(manifest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PET_LONG_VIDEO_FORBIDDEN_ASSET");
    }

    @Test
    void acceptedFixtureDoesNotContainForbiddenAssetMarker() throws Exception {
        ObjectNode manifest = PetLongVideoTestFixtures.manifest(objectMapper);

        orchestrator.validateManifest(manifest);

        assertThat(manifest.toString()).doesNotContain("pet-cat-front-white-brown-face.jpg");
    }
}
