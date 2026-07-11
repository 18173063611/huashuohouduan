package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class PetLongVideoTestFixtures {

    private PetLongVideoTestFixtures() {
    }

    static ObjectNode manifest(ObjectMapper objectMapper) throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "compositionId":"2787",
                  "title":"living room snack meeting",
                  "businessDomain":"pet",
                  "templateType":"PET_STORY_LONG_VIDEO",
                  "totalDurationSeconds":30,
                  "aspectRatio":"9:16",
                  "estimatedCredits":840,
                  "audit":{"bannedAssetCheck":true,"carPollutionCheck":true,"referenceImageCheck":true},
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
                      "providerPayload":{"businessDomain":"pet","prompt":"pet story dog cat owner dialogue","negativePrompt":"do not show cars"}
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
                      "providerPayload":{"businessDomain":"pet","prompt":"pet story owner assigns a small task","negativePrompt":"no unrelated brand logo"}
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
                      "providerPayload":{"businessDomain":"pet","prompt":"pet story warm ending","negativePrompt":"no watermark"}
                    }
                  ]
                }
                """);
    }
}
