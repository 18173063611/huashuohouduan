package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.asset.service.AssetService;
import com.huashuo.storage.StorageService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PetLongVideoResultAssetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetLongVideoResultAssetService service = new PetLongVideoResultAssetService(
            mock(AssetService.class),
            mock(StorageService.class),
            objectMapper
    );

    @Test
    void resultAssetPlanIsPetScopedAndContainsTraceability() throws Exception {
        ObjectNode manifest = (ObjectNode) objectMapper.readTree("""
                {
                  "businessDomain":"pet",
                  "templateType":"PET_STORY_LONG_VIDEO",
                  "compositionId":"2787",
                  "longVideoManifestId":88,
                  "title":"living room snack meeting",
                  "totalDurationSeconds":30,
                  "aspectRatio":"9:16",
                  "sourceMaterials":[
                    {"role":"main_pet","assetId":"2713","url":"https://cdn.example.com/dog.jpg"},
                    {"role":"second_pet","assetId":"2691","url":"https://cdn.example.com/cat.jpg"},
                    {"role":"scene","assetId":"2683","url":"https://cdn.example.com/room.jpg"}
                  ],
                  "segments":[
                    {"segmentIndex":1,"generationTaskId":101,"referenceAssetIds":["2713","2691"]},
                    {"segmentIndex":2,"generationTaskId":102,"referenceAssetIds":["2713","2691"]},
                    {"segmentIndex":3,"generationTaskId":103,"referenceAssetIds":["2713","2691"]}
                  ]
                }
                """);

        ObjectNode plan = service.buildResultAssetPlan(manifest);

        assertThat(plan.get("businessDomain").asText()).isEqualTo("pet");
        assertThat(plan.get("assetType").asText()).isEqualTo("PET_LONG_VIDEO_RESULT");
        assertThat(plan.get("sourceType").asText()).isEqualTo("PET_LONG_VIDEO_RESULT");
        assertThat(plan.get("segmentCount").asInt()).isEqualTo(3);
        assertThat(plan.get("sourceSegmentTaskIds")).hasSize(3);
        assertThat(plan.toString()).doesNotContain("car", "vehicle", "汽车");
    }
}
