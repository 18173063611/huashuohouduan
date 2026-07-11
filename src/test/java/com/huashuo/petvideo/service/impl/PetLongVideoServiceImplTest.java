package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.task.enums.TaskTypeCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PetLongVideoServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BillingEstimateService billingEstimateService = mock(BillingEstimateService.class);
    private final PetLongVideoGenerationOrchestrator generationOrchestrator = mock(PetLongVideoGenerationOrchestrator.class);
    private final PetLongVideoServiceImpl service = new PetLongVideoServiceImpl(
            objectMapper,
            new PetVideoPromptBuilder(objectMapper),
            billingEstimateService,
            generationOrchestrator
    );

    @Test
    void previewsThirtySecondPetStoryAsSegmentManifest() throws Exception {
        when(billingEstimateService.resolveCreditCost(eq(TaskTypeCode.SEEDANCE_REFERENCE_VIDEO), eq(null)))
                .thenReturn(220L);

        JsonNode preview = service.previewLongVideo(petStoryComposition(), 7L);

        assertThat(preview.get("businessDomain").asText()).isEqualTo("pet");
        assertThat(preview.get("templateType").asText()).isEqualTo("PET_STORY_LONG_VIDEO");
        assertThat(preview.get("totalDurationSeconds").asInt()).isEqualTo(30);
        assertThat(preview.get("segmentCount").asInt()).isEqualTo(3);
        assertThat(preview.get("estimatedCredits").asLong()).isGreaterThan(0L);

        JsonNode segments = preview.get("segments");
        assertThat(segments.get(0).get("durationSeconds").asInt()).isEqualTo(10);
        assertThat(segments.get(1).get("durationSeconds").asInt()).isEqualTo(13);
        assertThat(segments.get(2).get("durationSeconds").asInt()).isEqualTo(7);
        assertThat(segments.get(0).get("includedSceneIds").get(0).asText()).isEqualTo("scene_01");
        assertThat(segments.get(0).get("includedSceneIds").get(1).asText()).isEqualTo("scene_02");
        assertThat(segments.get(1).get("includedSceneIds").get(0).asText()).isEqualTo("scene_03");
        assertThat(segments.get(1).get("includedSceneIds").get(1).asText()).isEqualTo("scene_04");

        for (JsonNode segment : segments) {
            JsonNode payload = segment.get("providerPayload");
            assertThat(payload.get("businessDomain").asText()).isEqualTo("pet");
            assertThat(payload.get("templateType").asText()).isEqualTo("PET_STORY_LONG_VIDEO_SEGMENT");
            assertThat(payload.get("durationSeconds").asInt()).isBetween(4, 15);
            assertThat(payload.get("imageUrls")).hasSize(4);
            assertThat(payload.get("prompt").asText()).contains("客厅里的零食会议", "豆包", "栗子");
            assertThat(payload.get("prompt").asText()).doesNotContain("销售顾问", "试驾", "车型");
            assertThat(segment.get("localSubtitles")).isNotEmpty();
            assertThat(segment.get("referenceAssetIds")).hasSize(4);
            assertThat(segment.get("taskStatus").asText()).isEqualTo("pending");
            assertThat(segment.get("voiceMapping").get("dog_doubao").asText()).isEqualTo("voice_dog_doubao");
        }
        assertThat(preview.get("audit").get("bannedAssetCheck").asBoolean()).isTrue();
        assertThat(preview.get("audit").get("carPollutionCheck").asBoolean()).isTrue();
        assertThat(preview.get("stitching").get("outputSingleVideo").asBoolean()).isTrue();
    }

    private JsonNode petStoryComposition() throws Exception {
        return objectMapper.readTree("""
                {
                  "compositionId": "2787",
                  "title": "客厅里的零食会议",
                  "businessDomain": "pet",
                  "templateType": "PET_STORY_LONG_VIDEO",
                  "totalDurationSeconds": 30,
                  "aspectRatio": "9:16",
                  "style": "realistic",
                  "characters": [
                    {"roleId":"owner_linran","type":"human_avatar","displayName":"林然","assetId":"2786","voiceProfileId":"voice_owner_linran"},
                    {"roleId":"dog_doubao","type":"pet_dog","displayName":"豆包","assetId":"2713","voiceProfileId":"voice_dog_doubao"},
                    {"roleId":"cat_lizi","type":"pet_cat","displayName":"栗子","assetId":"2691","voiceProfileId":"voice_cat_lizi"}
                  ],
                  "materials": [
                    {"role":"main_pet","assetId":"2713","url":"https://example.com/dog.jpg","label":"豆包小狗主体参考"},
                    {"role":"second_pet","assetId":"2691","url":"https://example.com/cat.jpg","label":"栗子小猫主体参考"},
                    {"role":"human_avatar","assetId":"2786","url":"https://example.com/linran.png","label":"林然主人参考"},
                    {"role":"scene","assetId":"2683","url":"https://example.com/living-room.jpg","label":"客厅场景参考"}
                  ],
                  "globalScenes": [
                    {
                      "sceneId":"scene_01","start":0,"end":5,
                      "visual":"明亮客厅，中景。小狗豆包坐在地毯上，小猫栗子坐在沙发边，像在认真开会。",
                      "camera":"固定中景",
                      "dialogues":[{"speakerId":"dog_doubao","text":"今天的零食预算，可以申请翻倍吗？","subtitle":"豆包：今天的零食预算，可以申请翻倍吗？","start":0,"end":5,"voiceProfileId":"voice_dog_doubao"}]
                    },
                    {
                      "sceneId":"scene_02","start":5,"end":10,
                      "visual":"栗子淡定看向豆包，像在吐槽。",
                      "camera":"猫咪近景",
                      "dialogues":[{"speakerId":"cat_lizi","text":"先别激动，你上次把理由写成了因为我可爱。","subtitle":"栗子：先别激动，你上次把理由写成了因为我可爱。","start":5,"end":10,"voiceProfileId":"voice_cat_lizi"}]
                    },
                    {
                      "sceneId":"scene_03","start":10,"end":16,
                      "visual":"林然从画面一侧出镜，蹲下看着两只宠物。",
                      "camera":"中景三者同框",
                      "dialogues":[{"speakerId":"owner_linran","text":"会议我听见了。想加餐，可以先完成一个小任务。","subtitle":"林然：会议我听见了。想加餐，可以先完成一个小任务。","start":10,"end":16,"voiceProfileId":"voice_owner_linran"}]
                    },
                    {
                      "sceneId":"scene_04","start":16,"end":23,
                      "visual":"豆包坐好，栗子伸爪碰一下玩具球，像是勉强配合。",
                      "camera":"稳定中近景",
                      "dialogues":[
                        {"speakerId":"dog_doubao","text":"我准备好了！","subtitle":"豆包：我准备好了！","start":16,"end":19,"voiceProfileId":"voice_dog_doubao"},
                        {"speakerId":"cat_lizi","text":"我只是监督，不是被零食收买。","subtitle":"栗子：我只是监督，不是被零食收买。","start":19,"end":23,"voiceProfileId":"voice_cat_lizi"}
                      ]
                    },
                    {
                      "sceneId":"scene_05","start":23,"end":30,
                      "visual":"林然笑着拿出小零食，两只宠物一起看向镜头，温馨结尾。",
                      "camera":"固定近景",
                      "dialogues":[
                        {"speakerId":"owner_linran","text":"好，今天的会议通过。","subtitle":"林然：好，今天的会议通过。","start":23,"end":25,"voiceProfileId":"voice_owner_linran"},
                        {"speakerId":"dog_doubao","text":"那明天可以开早餐会议吗？","subtitle":"豆包：那明天可以开早餐会议吗？","start":25,"end":28,"voiceProfileId":"voice_dog_doubao"},
                        {"speakerId":"cat_lizi","text":"我建议先看看预算。","subtitle":"栗子：我建议先看看预算。","start":28,"end":30,"voiceProfileId":"voice_cat_lizi"}
                      ]
                    }
                  ]
                }
                """);
    }
}
