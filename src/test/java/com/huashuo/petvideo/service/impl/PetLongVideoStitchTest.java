package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PetLongVideoStitchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetLongVideoStitchService service = new PetLongVideoStitchService(objectMapper, "ffmpeg");

    @Test
    void dryRunStitchManifestMapsSegmentsAndSubtitleTimeline() throws Exception {
        ObjectNode manifest = (ObjectNode) objectMapper.readTree("""
                {
                  "businessDomain":"pet",
                  "templateType":"PET_STORY_LONG_VIDEO",
                  "compositionId":"2787",
                  "totalDurationSeconds":30,
                  "aspectRatio":"9:16",
                  "segments":[
                    {
                      "segmentIndex":1,
                      "stitchOrder":1,
                      "globalStart":0,
                      "globalEnd":10,
                      "durationSeconds":10,
                      "generationTaskId":101,
                      "taskStatus":"succeeded",
                      "resultUrl":"https://cdn.example.com/s1.mp4",
                      "referenceAssetIds":["2713","2691"],
                      "voiceMapping":{"dog":"voice_dog"},
                      "localSubtitles":[{"speakerId":"dog","text":"snack?","start":1,"end":4}]
                    },
                    {
                      "segmentIndex":2,
                      "stitchOrder":2,
                      "globalStart":10,
                      "globalEnd":23,
                      "durationSeconds":13,
                      "generationTaskId":102,
                      "taskStatus":"succeeded",
                      "resultUrl":"https://cdn.example.com/s2.mp4",
                      "referenceAssetIds":["2713","2691"],
                      "localSubtitles":[{"speakerId":"cat","text":"budget first","start":2,"end":5}]
                    },
                    {
                      "segmentIndex":3,
                      "stitchOrder":3,
                      "globalStart":23,
                      "globalEnd":30,
                      "durationSeconds":7,
                      "generationTaskId":103,
                      "taskStatus":"succeeded",
                      "resultUrl":"https://cdn.example.com/s3.mp4",
                      "referenceAssetIds":["2713","2691"],
                      "localSubtitles":[]
                    }
                  ]
                }
                """);

        ObjectNode stitch = service.buildDryRunStitchManifest(manifest);

        assertThat(stitch.get("readyForRealStitch").asBoolean()).isTrue();
        assertThat(stitch.get("segmentInputs")).hasSize(3);
        assertThat(stitch.get("subtitleTimeline")).hasSize(2);
        assertThat(stitch.get("subtitleTimeline").get(1).get("globalStart").asInt()).isEqualTo(12);
        assertThat(stitch.get("outputSingleVideo").asBoolean()).isTrue();
    }
}
