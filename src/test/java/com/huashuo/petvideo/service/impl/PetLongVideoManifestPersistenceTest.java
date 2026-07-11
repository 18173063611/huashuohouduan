package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetContent;
import com.huashuo.asset.vo.AssetItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PetLongVideoManifestPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssetService assetService = mock(AssetService.class);
    private final PetLongVideoManifestService service = new PetLongVideoManifestService(assetService, objectMapper);

    @Test
    void createsAndReloadsManifestAssetWithoutChangingManifestId() throws Exception {
        ObjectNode manifest = manifest();
        AssetItem created = asset(88L, "pet-long-video-manifest-2787-draft.json");
        when(assetService.createGeneratedJsonAsset(eq(7L), eq(null), eq(null), any(), any(), eq("storyboard"),
                eq("PET_LONG_VIDEO_MANIFEST"), any())).thenReturn(created);
        when(assetService.updateEditableTextAsset(eq(88L), any(), any(), any(), eq(OptionalLong.of(7L))))
                .thenReturn(asset(88L, "pet-long-video-manifest-2787-88.json"));
        when(assetService.getAssetForViewer(eq(88L), eq(OptionalLong.of(7L))))
                .thenReturn(asset(88L, "pet-long-video-manifest-2787-88.json"));
        when(assetService.getGeneratedAssetContent(eq(88L), eq(OptionalLong.of(7L))))
                .thenReturn(new AssetContent("manifest.json", "application/json",
                        "{\"businessDomain\":\"pet\",\"templateType\":\"PET_STORY_LONG_VIDEO\",\"totalDurationSeconds\":30,\"segments\":[]}"));

        service.createManifestAsset(manifest, 7L);
        ObjectNode loaded = service.loadManifest(88L, 7L);

        assertThat(manifest.get("longVideoManifestId").asLong()).isEqualTo(88L);
        assertThat(loaded.get("longVideoManifestId").asLong()).isEqualTo(88L);
        verify(assetService).updateEditableTextAsset(eq(88L), any(), any(), any(), eq(OptionalLong.of(7L)));
    }

    @Test
    void updatesExistingManifestAssetForResume() throws Exception {
        ObjectNode manifest = manifest();
        manifest.put("longVideoManifestId", 90L);
        manifest.withArray("segments").add(objectMapper.readTree("{\"segmentIndex\":1,\"generationTaskId\":123,\"taskStatus\":\"submitted\"}"));
        when(assetService.updateEditableTextAsset(eq(90L), any(), any(), any(), eq(OptionalLong.of(7L))))
                .thenReturn(asset(90L, "pet-long-video-manifest-2787-90.json"));

        service.ensurePersisted(manifest, 7L);

        verify(assetService).updateEditableTextAsset(eq(90L), any(), any(), any(), eq(OptionalLong.of(7L)));
    }

    private ObjectNode manifest() {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("compositionId", "2787");
        manifest.put("title", "living room snack meeting");
        manifest.put("businessDomain", "pet");
        manifest.put("templateType", "PET_STORY_LONG_VIDEO");
        manifest.put("totalDurationSeconds", 30);
        manifest.put("segmentCount", 3);
        manifest.put("estimatedCredits", 840);
        manifest.set("segments", objectMapper.createArrayNode());
        return manifest;
    }

    private AssetItem asset(Long id, String fileName) {
        return new AssetItem(
                id,
                7L,
                7L,
                null,
                null,
                "JSON",
                "GENERATED",
                "PRIVATE",
                "ACTIVE",
                null,
                fileName,
                "story/" + fileName,
                "https://cdn.example.com/" + fileName,
                null,
                "application/json",
                128L,
                "PET_LONG_VIDEO_MANIFEST",
                "分镜",
                "{}",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
