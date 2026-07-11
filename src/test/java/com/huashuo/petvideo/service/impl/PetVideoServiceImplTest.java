package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.common.ai.ArkChatResult;
import com.huashuo.common.ai.ArkTextClient;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import com.huashuo.petvideo.dto.PetWorkForkRequest;
import com.huashuo.petvideo.dto.PetVideoPreviewResponse;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.dto.PetWorkResponse;
import com.huashuo.petvideo.entity.PetVideoWorkEntity;
import com.huashuo.petvideo.mapper.PetVideoWorkMapper;
import com.huashuo.storage.StorageService;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.service.VideoAsyncTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PetVideoServiceImplTest {

    private final PetVideoWorkMapper workMapper = mock(PetVideoWorkMapper.class);
    private final VideoAsyncTaskService videoAsyncTaskService = mock(VideoAsyncTaskService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final BillingEstimateService billingEstimateService = mock(BillingEstimateService.class);
    private final ArkTextClient arkTextClient = mock(ArkTextClient.class);
    private final StorageService storageService = mock(StorageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetVideoServiceImpl service = service(true);

    private PetVideoServiceImpl service(boolean providerSubmitEnabled) {
        PetVideoProperties properties = new PetVideoProperties();
        properties.setProviderSubmitEnabled(providerSubmitEnabled);
        properties.setDryRunEnabled(true);
        return new PetVideoServiceImpl(
                workMapper,
                videoAsyncTaskService,
                taskService,
                objectMapper,
                new PetCreationDraftValidator(objectMapper),
                new PetVideoPromptBuilder(objectMapper),
                billingEstimateService,
                properties,
                arkTextClient,
                storageService,
                "doubao-pet-script",
                "seedance-text-model",
                "seedance-reference-model"
        );
    }

    @Test
    void generateScriptUsesArkDialogueDraftWhenAvailable() throws Exception {
        when(arkTextClient.available()).thenReturn(true);
        when(arkTextClient.chat(any(), eq("doubao-pet-script"), any(Duration.class))).thenReturn(new ArkChatResult("""
                {
                  "scriptText": "小猫先嘴硬解释自己没有闯祸，小狗逐步拿出线索，最后两只宠物一起卖萌收尾。",
                  "dialogueLines": [
                    {"speakerRoleId":"main-cat","text":"我只是路过，真的没有偷玩纸巾。","emotion":"认真解释","speed":"normal","voiceName":"软萌童声","lipSync":true},
                    {"speakerRoleId":"second-dog","text":"那你爪子上的纸屑怎么解释？","emotion":"吐槽","speed":"normal","voiceName":"机智少年音","lipSync":true}
                  ],
                  "visualSettings": {"cameraRhythm":"short_drama","expressionIntensity":88}
                }
                """, "doubao-pet-script", 10, 20, 30, "{}"));

        var response = service.generateScript(objectMapper.readTree("""
                {
                  "prompt": "小猫把纸巾弄得到处都是，被小狗发现后努力解释",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 15,
                  "style": "funny",
                  "lipSyncEnabled": true,
                  "roles": [
                    {"id":"main-cat","name":"奶盖","type":"cat","personalityTags":["嘴硬"],"speakingTone":"软萌"},
                    {"id":"second-dog","name":"布丁","type":"dog","personalityTags":["机智"],"speakingTone":"吐槽"}
                  ],
                  "dialogueLines": [
                    {"id":"old","speakerRoleId":"main-cat","text":"旧台词","emotion":"委屈","speed":"normal","voiceName":"旧音色","lipSync":true}
                  ],
                  "scriptText": "旧文案"
                }
                """), 7L);

        assertEquals("dialogue_video", response.get("generationMode").asText());
        assertEquals("小猫先嘴硬解释自己没有闯祸，小狗逐步拿出线索，最后两只宠物一起卖萌收尾。", response.get("scriptText").asText());
        assertEquals(2, response.get("dialogueLines").size());
        assertEquals("那你爪子上的纸屑怎么解释？", response.get("dialogueLines").get(1).get("text").asText());
        assertEquals("short_drama", response.get("visualSettings").get("cameraRhythm").asText());
        assertEquals("volcengine_ark", response.get("diagnostics").get("scriptModelSource").asText());
    }

    @Test
    void generateScriptSendsMaterialsDialogueAndStoryboardContextToArk() throws Exception {
        when(arkTextClient.available()).thenReturn(true);
        when(arkTextClient.chat(any(), eq("doubao-pet-script"), any(Duration.class))).thenReturn(new ArkChatResult("""
                {
                  "scriptText": "两只宠物围绕玩具误会展开对话，最后用一个可爱反转收尾。",
                  "dialogueLines": [
                    {"speakerRoleId":"main-pet","text":"我只是先帮你检查一下玩具。","emotion":"认真解释","speed":"normal","voiceName":"软萌童声","lipSync":true},
                    {"speakerRoleId":"second-pet","text":"那为什么它在你爪子下面？","emotion":"吐槽","speed":"normal","voiceName":"机智少年音","lipSync":true}
                  ]
                }
                """, "doubao-pet-script", 10, 20, 30, "{}"));

        service.generateScript(objectMapper.readTree("""
                {
                  "prompt": "两只宠物因为玩具发生误会，结尾要轻松可爱",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 10,
                  "style": "funny",
                  "lipSyncEnabled": true,
                  "roles": [
                    {"id":"main-pet","name":"奶盖","type":"cat","personalityTags":["会撒娇"],"speakingTone":"软萌"},
                    {"id":"second-pet","name":"布丁","type":"dog","personalityTags":["机智"],"speakingTone":"吐槽"}
                  ],
                  "materials": [
                    {"id":"mat-main","role":"main_pet","assetId":"1001","url":"https://example.com/main-cat.png","label":"main cat reference"},
                    {"id":"mat-second","role":"second_pet","assetId":"1002","url":"https://example.com/second-dog.png","label":"second dog reference"},
                    {"id":"mat-scene","role":"scene","assetId":"1003","url":"https://example.com/living-room.png","label":"cozy living room"}
                  ],
                  "dialogueLines": [
                    {"id":"manual-1","speakerRoleId":"main-pet","text":"我只是先帮你检查一下玩具。","emotion":"认真解释","speed":"normal","voiceName":"软萌童声","lipSync":true}
                  ],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":3,"frameDescription":"living room shot with both pets separate","characterAction":"main pet looks at toy","cameraMove":"stable medium shot","subtitle":"manual subtitle"}
                  ]
                }
                """), 7L);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(arkTextClient).chat(promptCaptor.capture(), eq("doubao-pet-script"), timeoutCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("素材清单"));
        assertTrue(prompt.contains("main_pet"));
        assertTrue(prompt.contains("second_pet"));
        assertTrue(prompt.contains("cozy living room"));
        assertTrue(prompt.contains("已有台词"));
        assertTrue(prompt.contains("我只是先帮你检查一下玩具"));
        assertTrue(prompt.contains("已有分镜"));
        assertTrue(prompt.contains("living room shot"));
        assertTrue(prompt.contains("适配规则"));
        assertEquals(22L, timeoutCaptor.getValue().toSeconds());
    }

    @Test
    void generateScriptFallsBackWithoutOverwritingManualDialogueWhenArkFails() throws Exception {
        when(arkTextClient.available()).thenReturn(true);
        when(arkTextClient.chat(any(), eq("doubao-pet-script"), any(Duration.class)))
                .thenThrow(new BusinessException(50214, "timeout"));

        JsonNode response = service.generateScript(objectMapper.readTree("""
                {
                  "prompt": "宠物因为小玩具产生误会",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 10,
                  "style": "funny",
                  "lipSyncEnabled": true,
                  "roles": [
                    {"id":"main-pet","name":"奶盖","type":"cat"},
                    {"id":"second-pet","name":"布丁","type":"dog"}
                  ],
                  "dialogueLines": [
                    {"id":"manual-1","speakerRoleId":"main-pet","text":"这是我手动填写的台词。","emotion":"认真解释","speed":"normal","voiceName":"软萌童声","lipSync":true}
                  ]
                }
                """), 7L);

        assertEquals("这是我手动填写的台词。", response.get("dialogueLines").get(0).get("text").asText());
        assertEquals("local_template_fallback", response.get("diagnostics").get("scriptModelSource").asText());
        assertEquals(1, response.get("diagnostics").get("scriptContextDialogueCount").asInt());
    }

    @Test
    void generateStoryboardAdaptsToCurrentDialogueAndSceneMaterial() throws Exception {
        JsonNode response = service.generateStoryboard(objectMapper.readTree("""
                {
                  "prompt": "两只宠物在客厅讨论谁把玩具藏起来了",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 10,
                  "style": "funny",
                  "lipSyncEnabled": true,
                  "roles": [
                    {"id":"main-pet","name":"奶盖","type":"cat"},
                    {"id":"second-pet","name":"布丁","type":"dog"}
                  ],
                  "materials": [
                    {"id":"mat-main","role":"main_pet","url":"https://example.com/main-cat.png","label":"main cat reference"},
                    {"id":"mat-second","role":"second_pet","url":"https://example.com/second-dog.png","label":"second dog reference"},
                    {"id":"mat-scene","role":"scene","url":"https://example.com/living-room.png","label":"cozy living room"}
                  ],
                  "dialogueLines": [
                    {"id":"line-1","speakerRoleId":"main-pet","text":"我真的只是路过。","emotion":"认真解释","speed":"normal","voiceName":"软萌童声","lipSync":true},
                    {"id":"line-2","speakerRoleId":"second-pet","text":"那玩具为什么在你身后？","emotion":"吐槽","speed":"normal","voiceName":"机智少年音","lipSync":true}
                  ]
                }
                """), 7L);

        assertEquals("我真的只是路过。", response.get("shots").get(0).get("subtitle").asText());
        assertEquals("那玩具为什么在你身后？", response.get("shots").get(1).get("subtitle").asText());
        assertTrue(response.get("shots").get(0).get("frameDescription").asText().contains("cozy living room"));
        assertEquals(3, response.get("shots").size());
        verifyNoInteractions(arkTextClient);
    }

    @Test
    void createTaskWithReferenceMaterialCreatesSeedanceReferenceTaskAndWork() throws Exception {
        when(videoAsyncTaskService.createReferenceVideoTask(any(ImageReferenceDTO.class), eq("trace-pet"), eq(7L),
                isNull(), any(), any())).thenReturn(task(100L, TaskStatusCode.QUEUED));
        when(workMapper.selectOne(any())).thenReturn(null);
        when(workMapper.insert(any(PetVideoWorkEntity.class))).thenAnswer(invocation -> {
            PetVideoWorkEntity work = invocation.getArgument(0);
            work.setWorkId(500L);
            return 1;
        });

        PetVideoTaskResponse response = service.createTask(objectMapper.readTree("""
                {
                  "prompt": "主宠小猫在客厅里认真解释自己为什么偷吃零食",
                  "videoType": "monologue",
                  "generationMode": "reference_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 15,
                  "style": "cute",
                  "visualSettings": {"backgroundPrompt":"温暖客厅背景，浅景深，主体宠物清晰突出","productPrompt":"宠物零食袋自然放在右侧，不能遮挡主宠脸部"},
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["嘴硬"],"speakingTone":"软萌"}],
                  "materials": [
                    {"id":"mat-1","role":"main_pet","url":"https://example.com/cat.png","label":"主宠"},
                    {"id":"mat-2","role":"prop","url":"https://example.com/snack.png","label":"宠物零食"}
                  ],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":5,"frameDescription":"小猫坐在客厅地毯上看镜头","characterAction":"小猫眨眼并低头","cameraMove":"稳定推近","subtitle":"我只是闻了一下"},
                    {"id":"shot-2","index":2,"durationSeconds":5,"frameDescription":"镜头靠近小猫嘴边零食碎屑","characterAction":"小猫假装无辜","cameraMove":"轻微跟拍","subtitle":"真的没有偷吃"},
                    {"id":"shot-3","index":3,"durationSeconds":5,"frameDescription":"小猫抬头撒娇收尾","characterAction":"小猫歪头看向主人","cameraMove":"固定近景","subtitle":"下次分你一点"}
                  ]
                }
                """), 7L, "trace-pet", "idem-1");

        assertEquals("100", response.id());
        assertEquals("500", response.workId());
        assertEquals("queued", response.status());

        ArgumentCaptor<ImageReferenceDTO> requestCaptor = ArgumentCaptor.forClass(ImageReferenceDTO.class);
        verify(videoAsyncTaskService).createReferenceVideoTask(requestCaptor.capture(), eq("trace-pet"), eq(7L),
                isNull(), any(), any());
        assertEquals("9:16", requestCaptor.getValue().getRatio());
        assertEquals(15, requestCaptor.getValue().getDuration());
        assertEquals("https://example.com/cat.png", requestCaptor.getValue().getImageUrls().get(0));
        assertEquals("pet_creation", requestCaptor.getValue().getBusinessType());
        assertTrue(requestCaptor.getValue().getPrompt().contains("温暖客厅背景"));
        assertTrue(requestCaptor.getValue().getPrompt().contains("宠物零食袋自然放在右侧"));
        assertEquals("pet-video-prompt-v4", requestCaptor.getValue().getDiagnosticMetadata().get("promptVersion").asText());
        assertTrue(requestCaptor.getValue().getPrompt().contains("Reference image manifest"));
        assertTrue(requestCaptor.getValue().getPrompt().contains("Image 1 = main_pet"));
        assertTrue(requestCaptor.getValue().getPrompt().contains("Image 2 = prop"));
        assertTrue(requestCaptor.getValue().getPrompt().contains("the image wins"));
        assertTrue(requestCaptor.getValue().getDiagnosticMetadata().has("draftSnapshot"));
        assertTrue(requestCaptor.getValue().getDiagnosticMetadata().has("materialSummary"));
        assertTrue(requestCaptor.getValue().getDiagnosticMetadata().has("shotSummary"));

        ArgumentCaptor<PetVideoWorkEntity> workCaptor = ArgumentCaptor.forClass(PetVideoWorkEntity.class);
        verify(workMapper).insert(workCaptor.capture());
        assertEquals(7L, workCaptor.getValue().getOwnerUserId());
        assertEquals(100L, workCaptor.getValue().getTaskId());
        assertEquals("cat", workCaptor.getValue().getPetType());
        assertEquals(15, workCaptor.getValue().getDurationSeconds());
        assertTrue(workCaptor.getValue().getDraftJson().contains("偷吃零食"));
    }

    @Test
    void createTaskWithoutMainPetRejectsUnlessExplicitTextVideo() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.createTask(objectMapper.readTree("""
                {
                  "prompt": "小猫在客厅解释自己为什么把玩具藏起来",
                  "generationMode": "reference_video",
                  "videoType": "monologue",
                  "aspectRatio": "9:16",
                  "durationSeconds": 15,
                  "style": "cute",
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["嘴硬"],"speakingTone":"软萌"}],
                  "materials": [],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":5,"frameDescription":"小猫坐在地毯上","characterAction":"小猫看向镜头","cameraMove":"稳定推近","subtitle":"我没有藏起来"},
                    {"id":"shot-2","index":2,"durationSeconds":5,"frameDescription":"镜头扫过玩具箱","characterAction":"小猫低头","cameraMove":"轻微跟拍","subtitle":"只是换个地方"},
                    {"id":"shot-3","index":3,"durationSeconds":5,"frameDescription":"小猫歪头收尾","characterAction":"小猫撒娇","cameraMove":"固定近景","subtitle":"你会原谅我吧"}
                  ]
                }
                """), 7L, "trace-pet", "idem-no-main"));

        assertTrue(ex.getMessage().contains("主宠物参考图"));
    }

    @Test
    void explicitTextVideoAllowsNoMainPetAndCreatesTextTask() throws Exception {
        when(videoAsyncTaskService.createTextVideoTask(any(TextDTO.class), eq("trace-pet"), eq(7L),
                isNull(), any(), any())).thenReturn(task(101L, TaskStatusCode.QUEUED));
        when(workMapper.selectOne(any())).thenReturn(null);
        when(workMapper.insert(any(PetVideoWorkEntity.class))).thenAnswer(invocation -> {
            PetVideoWorkEntity work = invocation.getArgument(0);
            work.setWorkId(501L);
            return 1;
        });

        PetVideoTaskResponse response = service.createTask(objectMapper.readTree("""
                {
                  "prompt": "小猫在客厅解释自己为什么把玩具藏起来",
                  "generationMode": "text_video",
                  "videoType": "monologue",
                  "aspectRatio": "9:16",
                  "durationSeconds": 15,
                  "style": "cute",
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["嘴硬"],"speakingTone":"软萌"}],
                  "materials": [],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":5,"frameDescription":"小猫坐在地毯上","characterAction":"小猫看向镜头","cameraMove":"稳定推近","subtitle":"我没有藏起来"},
                    {"id":"shot-2","index":2,"durationSeconds":5,"frameDescription":"镜头扫过玩具箱","characterAction":"小猫低头","cameraMove":"轻微跟拍","subtitle":"只是换个地方"},
                    {"id":"shot-3","index":3,"durationSeconds":5,"frameDescription":"小猫歪头收尾","characterAction":"小猫撒娇","cameraMove":"固定近景","subtitle":"你会原谅我吧"}
                  ]
                }
                """), 7L, "trace-pet", "idem-text");

        assertEquals("101", response.id());
        ArgumentCaptor<TextDTO> requestCaptor = ArgumentCaptor.forClass(TextDTO.class);
        verify(videoAsyncTaskService).createTextVideoTask(requestCaptor.capture(), eq("trace-pet"), eq(7L),
                isNull(), any(), any());
        assertEquals("pet_creation", requestCaptor.getValue().getBusinessType());
        assertEquals("text_video", requestCaptor.getValue().getDiagnosticMetadata().get("generationMode").asText());
    }

    @Test
    void previewTaskReturnsDryRunPayloadWithoutSubmittingProvider() throws Exception {
        when(billingEstimateService.estimate(any())).thenReturn(new BillingEstimateResponse(
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                228L,
                "task",
                "seedance-reference-model",
                "seedance",
                "PET_DYNAMIC_ESTIMATE",
                1000L,
                true,
                java.util.List.of()
        ));

        PetVideoPreviewResponse response = service(false).previewTask(objectMapper.readTree("""
                {
                  "prompt": "橘白小猫晚上偷偷溜到客厅，被灯光照到后立刻装无辜",
                  "videoType": "short_drama",
                  "generationMode": "reference_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 5,
                  "style": "cute",
                  "subtitleEnabled": true,
                  "voiceEnabled": false,
                  "lipSyncEnabled": false,
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["好奇","嘴硬"],"speakingTone":"软萌"}],
                  "materials": [{"id":"mat-1","role":"main_pet","url":"https://example.com/cat.png","label":"主宠"}],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":1,"frameDescription":"夜晚客厅，小猫从沙发边探头","characterAction":"小猫眼神好奇","cameraMove":"低机位轻推","subtitle":"我只是出来看看月亮"},
                    {"id":"shot-2","index":2,"durationSeconds":1,"frameDescription":"小猫轻手轻脚走向桌边","characterAction":"尾巴微微晃动","cameraMove":"柔和跟拍","subtitle":"真的没有想偷吃"},
                    {"id":"shot-3","index":3,"durationSeconds":1,"frameDescription":"灯突然亮起","characterAction":"小猫瞬间停住眼睛睁大","cameraMove":"快速定格","subtitle":"糟糕"},
                    {"id":"shot-4","index":4,"durationSeconds":1,"frameDescription":"小猫慢慢坐直","characterAction":"假装什么都没发生","cameraMove":"固定近景","subtitle":"你信吗"},
                    {"id":"shot-5","index":5,"durationSeconds":1,"frameDescription":"小猫歪头卖萌收尾","characterAction":"露出无辜表情","cameraMove":"轻微推近","subtitle":"我很乖吧"}
                  ]
                }
                """), 7L);

        assertTrue(response.dryRun());
        assertEquals(false, response.providerSubmitEnabled());
        assertEquals(false, response.providerSubmitted());
        assertEquals(false, response.taskCreated());
        assertEquals("PROVIDER_SUBMIT_DISABLED", response.errorCode());
        assertEquals("reference_video", response.generationMode());
        assertEquals(228L, response.estimatedCreditCost());
        assertTrue(response.promptPreview().contains("橘白小猫"));
        assertTrue(response.negativePrompt().contains("No car sales script"));
        assertEquals("seedance-reference-model", response.payloadPreview().get("modelCode").asText());
        assertEquals(false, response.payloadPreview().get("providerSubmitted").asBoolean());
        assertEquals("https://example.com/cat.png", response.payloadPreview().get("imageUrls").get(0).asText());
        verifyNoInteractions(videoAsyncTaskService);
    }

    @Test
    void previewRejectsProviderUnsupportedDurationBeforeBillingOrSubmit() throws Exception {
        BusinessException ex = assertThrows(BusinessException.class, () -> service(false).previewTask(objectMapper.readTree("""
                {
                  "prompt": "小狗和小猫在客厅开一个有对白的小会议",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 22,
                  "style": "realistic",
                  "voiceEnabled": true,
                  "lipSyncEnabled": true,
                  "subtitleEnabled": true,
                  "roles": [
                    {"id":"dog","name":"豆包","type":"dog","personalityTags":["认真"],"speakingTone":"活泼"},
                    {"id":"cat","name":"栗子","type":"cat","personalityTags":["冷静"],"speakingTone":"淡定"}
                  ],
                  "materials": [
                    {"id":"mat-1","role":"main_pet","url":"https://example.com/dog.png","label":"主宠"},
                    {"id":"mat-2","role":"second_pet","url":"https://example.com/cat.png","label":"第二宠物"}
                  ],
                  "dialogueLines": [
                    {"id":"line-1","speakerRoleId":"dog","text":"今天可以加餐吗","emotion":"开心","speed":"normal","voiceName":"dog","lipSync":true}
                  ],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":10,"frameDescription":"狗和猫在客厅同框","characterAction":"狗看向猫","cameraMove":"稳定中景","subtitle":"今天可以加餐吗"},
                    {"id":"shot-2","index":2,"durationSeconds":12,"frameDescription":"猫冷静回应","characterAction":"猫眨眼","cameraMove":"固定近景","subtitle":"先完成任务"}
                  ]
                }
                """), 7L));

        assertTrue(ex.getMessage().contains("durationSeconds"));
        verifyNoInteractions(billingEstimateService, videoAsyncTaskService);
    }

    @Test
    void previewTaskExposesStickerOverlayInPromptAndPayload() throws Exception {
        when(billingEstimateService.estimate(any())).thenReturn(new BillingEstimateResponse(
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                220L,
                "task",
                "seedance-reference-model",
                "seedance",
                "PET_DYNAMIC_ESTIMATE",
                1000L,
                true,
                java.util.List.of()
        ));

        PetVideoPreviewResponse response = service(false).previewTask(stickerDraft(), 7L);

        JsonNode overlay = response.payloadPreview().get("stickerOverlay");
        assertEquals("Oops", overlay.get("text").asText());
        assertEquals("Arial Black", overlay.get("fontFamily").asText());
        assertEquals(34, overlay.get("fontSize").asInt());
        assertEquals("#ff3366", overlay.get("textColor").asText());
        assertEquals("strong", overlay.get("strokeStyle").asText());
        assertEquals(41, overlay.get("textX").asInt());
        assertEquals(78, overlay.get("textY").asInt());
        assertEquals("sparkle", overlay.get("icon").asText());
        assertEquals(73, overlay.get("iconX").asInt());
        assertEquals(19, overlay.get("iconY").asInt());
        assertEquals("gif", overlay.get("dynamicFormat").asText());
        assertTrue(response.promptPreview().contains("Sticker overlay and output"));
        assertTrue(response.promptPreview().contains("fontSize=34"));
        assertTrue(response.promptPreview().contains("textColor=#ff3366"));
        assertTrue(response.promptPreview().contains("textPosition x=41, y=78"));
        assertTrue(response.promptPreview().contains("icon=sparkle"));
    }

    @Test
    void previewStoryDemoIncludesHumanAvatarAndStructuredStoryPayload() throws Exception {
        when(billingEstimateService.estimate(any())).thenReturn(new BillingEstimateResponse(
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                290L,
                "task",
                "seedance-reference-model",
                "seedance",
                "PET_DYNAMIC_ESTIMATE",
                1000L,
                true,
                java.util.List.of()
        ));

        PetVideoPreviewResponse response = service(false).previewTask(storyDemoDraft(), 7L);

        assertEquals("pet-video-prompt-v4", response.payloadPreview().get("diagnosticMetadata").get("promptVersion").asText());
        assertEquals("https://example.com/doubao.png", response.payloadPreview().get("imageUrls").get(0).asText());
        assertEquals("https://example.com/lizi.png", response.payloadPreview().get("imageUrls").get(1).asText());
        assertEquals("https://example.com/linran.png", response.payloadPreview().get("imageUrls").get(2).asText());
        assertEquals(3, response.payloadPreview().get("characters").size());
        assertEquals(3, response.payloadPreview().get("storyboard").size());
        assertEquals(4, response.payloadPreview().get("dialogues").size());
        assertTrue(response.payloadPreview().get("subtitles").get("enabled").asBoolean());
        assertTrue(response.payloadPreview().get("audioConfig").isObject());
        assertEquals("owner-warm-female", response.payloadPreview().get("voiceProfileId").asText());
        assertTrue(response.promptPreview().contains("Finished story requirement"));
        assertTrue(response.promptPreview().contains("Human cast"));
        assertTrue(response.promptPreview().contains("Structured story"));
        assertTrue(response.promptPreview().contains("客厅里的零食会议"));
        assertTrue(response.promptPreview().contains("零食会议"));
        assertTrue(response.promptPreview().contains("林然"));
        assertTrue(response.promptPreview().contains("Subtitle config"));
        assertTrue(response.promptPreview().contains("Audio config"));
        assertTrue(response.promptPreview().contains("不要生成无剧情视频"));
        assertEquals("human_avatar", response.payloadPreview().get("diagnosticMetadata")
                .get("materialSummary").get(2).get("role").asText());
    }

    @Test
    void createTaskPassesStickerOverlayToProviderPromptAndDiagnostics() throws Exception {
        when(videoAsyncTaskService.createReferenceVideoTask(any(ImageReferenceDTO.class), eq("trace-sticker"), eq(7L),
                isNull(), any(), any())).thenReturn(task(102L, TaskStatusCode.QUEUED));
        when(workMapper.selectOne(any())).thenReturn(null);
        when(workMapper.insert(any(PetVideoWorkEntity.class))).thenAnswer(invocation -> {
            PetVideoWorkEntity work = invocation.getArgument(0);
            work.setWorkId(502L);
            return 1;
        });

        PetVideoTaskResponse response = service(true).createTask(stickerDraft(), 7L, "trace-sticker", "idem-sticker");

        assertEquals("102", response.id());
        ArgumentCaptor<ImageReferenceDTO> requestCaptor = ArgumentCaptor.forClass(ImageReferenceDTO.class);
        verify(videoAsyncTaskService).createReferenceVideoTask(requestCaptor.capture(), eq("trace-sticker"), eq(7L),
                isNull(), any(), any());
        ImageReferenceDTO request = requestCaptor.getValue();
        assertTrue(request.getPrompt().contains("Sticker overlay and output"));
        assertTrue(request.getPrompt().contains("fontSize=34"));
        assertTrue(request.getPrompt().contains("textColor=#ff3366"));
        assertTrue(request.getPrompt().contains("textPosition x=41, y=78"));
        assertTrue(request.getPrompt().contains("icon=sparkle"));
        JsonNode overlay = request.getDiagnosticMetadata().get("stickerOverlay");
        assertEquals("Oops", overlay.get("text").asText());
        assertEquals("gif", overlay.get("dynamicFormat").asText());
        assertEquals("pet-sticker", request.getDiagnosticMetadata().get("draftSnapshot").get("templateId").asText());
    }

    @Test
    void getTaskReusesExistingStickerGifWithoutReuploadingOverlay() throws Exception {
        PetVideoWorkEntity work = new PetVideoWorkEntity();
        work.setWorkId(601L);
        work.setOwnerUserId(7L);
        work.setTaskId(104L);
        work.setTitle("pet sticker");
        work.setStatus("COMPLETED");
        work.setAspectRatio("1:1");
        work.setDurationSeconds(5);
        work.setDraftJson(objectMapper.writeValueAsString(stickerDraft()));
        work.setVideoUrl("https://cdn.example.com/video/processed-sticker.gif");
        work.setDeleted(0);
        when(workMapper.selectOne(any())).thenReturn(work);
        when(taskService.getTaskForViewer(eq(104L), any())).thenReturn(task(104L, TaskStatusCode.SUCCESS,
                "{\"videoUrl\":\"https://provider.example.com/raw-sticker.mp4\"}"));

        PetVideoTaskResponse response = service.getTask(104L, 7L);

        assertEquals("https://cdn.example.com/video/processed-sticker.gif", response.previewUrl());
        verifyNoInteractions(storageService);
    }

    @Test
    void previewAndCreateUsePetDynamicCreditsWhenTemplateAddsMaterialsAndAudio() throws Exception {
        when(billingEstimateService.estimate(any())).thenReturn(new BillingEstimateResponse(
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                220L,
                "task",
                "seedance-reference-model",
                "seedance",
                "TASK_CREDIT_PROPERTIES",
                1000L,
                true,
                java.util.List.of()
        ));
        PetVideoPreviewResponse preview = service(false).previewTask(multiPetDialogueDraft(), 7L);

        assertEquals(270L, preview.estimatedCreditCost());
        assertEquals(270L, preview.payloadPreview().get("estimatedCreditCost").asLong());

        when(videoAsyncTaskService.createReferenceVideoTask(any(ImageReferenceDTO.class), eq("trace-dialogue"), eq(7L),
                isNull(), any(), eq(270L))).thenReturn(task(103L, TaskStatusCode.QUEUED));
        when(workMapper.selectOne(any())).thenReturn(null);
        when(workMapper.insert(any(PetVideoWorkEntity.class))).thenAnswer(invocation -> {
            PetVideoWorkEntity work = invocation.getArgument(0);
            work.setWorkId(503L);
            return 1;
        });

        service(true).createTask(multiPetDialogueDraft(), 7L, "trace-dialogue", "idem-dialogue");

        verify(videoAsyncTaskService).createReferenceVideoTask(any(ImageReferenceDTO.class), eq("trace-dialogue"), eq(7L),
                isNull(), any(), eq(270L));
    }

    @Test
    void createTaskWhenProviderDisabledRejectsBeforeSubmit() throws Exception {
        PetVideoServiceImpl disabledService = service(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> disabledService.createTask(objectMapper.readTree("""
                {
                  "prompt": "橘白小猫晚上偷偷溜到客厅，被灯光照到后立刻装无辜",
                  "videoType": "short_drama",
                  "generationMode": "reference_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 5,
                  "style": "cute",
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["好奇"],"speakingTone":"软萌"}],
                  "materials": [{"id":"mat-1","role":"main_pet","url":"https://example.com/cat.png","label":"主宠"}],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":1,"frameDescription":"夜晚客厅，小猫从沙发边探头","characterAction":"小猫眼神好奇","cameraMove":"低机位轻推","subtitle":"我只是出来看看月亮"},
                    {"id":"shot-2","index":2,"durationSeconds":1,"frameDescription":"小猫轻手轻脚走向桌边","characterAction":"尾巴微微晃动","cameraMove":"柔和跟拍","subtitle":"真的没有想偷吃"},
                    {"id":"shot-3","index":3,"durationSeconds":1,"frameDescription":"灯突然亮起","characterAction":"小猫瞬间停住眼睛睁大","cameraMove":"快速定格","subtitle":"糟糕"},
                    {"id":"shot-4","index":4,"durationSeconds":1,"frameDescription":"小猫慢慢坐直","characterAction":"假装什么都没发生","cameraMove":"固定近景","subtitle":"你信吗"},
                    {"id":"shot-5","index":5,"durationSeconds":1,"frameDescription":"小猫歪头卖萌收尾","characterAction":"露出无辜表情","cameraMove":"轻微推近","subtitle":"我很乖吧"}
                  ]
                }
                """), 7L, "trace-pet", "idem-disabled"));

        assertEquals(50300, ex.getCode());
        assertTrue(ex.getMessage().contains("PROVIDER_SUBMIT_DISABLED"));
        verifyNoInteractions(videoAsyncTaskService);
    }

    @Test
    void forkWorkPersistsDraftCopyWithRequestedAspectRatio() throws Exception {
        PetVideoWorkEntity source = new PetVideoWorkEntity();
        source.setWorkId(10L);
        source.setOwnerUserId(7L);
        source.setTitle("原作品");
        source.setStatus("COMPLETED");
        source.setPetType("dog");
        source.setAspectRatio("9:16");
        source.setDurationSeconds(15);
        source.setDraftJson("""
                {
                  "prompt": "狗狗整理玩具",
                  "aspectRatio": "9:16",
                  "durationSeconds": 15,
                  "roles": [{"id":"role-main","name":"豆豆","type":"dog"}],
                  "materials": []
                }
                """);
        source.setCreatedAt(LocalDateTime.now());
        source.setDeleted(0);
        when(workMapper.selectOne(any())).thenReturn(source);
        when(workMapper.insert(any(PetVideoWorkEntity.class))).thenAnswer(invocation -> {
            PetVideoWorkEntity work = invocation.getArgument(0);
            work.setWorkId(11L);
            return 1;
        });

        PetWorkResponse response = service.forkWork(10L, new PetWorkForkRequest("16:9"), 7L);

        assertEquals("11", response.id());
        assertEquals("draft", response.status());
        assertEquals("16:9", response.aspectRatio());
        assertEquals("16:9", response.draft().get("aspectRatio").asText());

        ArgumentCaptor<PetVideoWorkEntity> workCaptor = ArgumentCaptor.forClass(PetVideoWorkEntity.class);
        verify(workMapper).insert(workCaptor.capture());
        assertEquals(10L, workCaptor.getValue().getSourceWorkId());
        assertEquals("DRAFT", workCaptor.getValue().getStatus());
        assertTrue(workCaptor.getValue().getDraftJson().contains("\"aspectRatio\":\"16:9\""));
    }

    private TaskItem task(Long taskId, String status) {
        return task(taskId, status, "{}");
    }

    private TaskItem task(Long taskId, String status, String outputJson) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskItem(
                taskId,
                null,
                7L,
                TaskTypeCode.SEEDANCE_REFERENCE_VIDEO,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L,
                "NONE",
                0L,
                null,
                "宠物视频",
                status,
                0,
                null,
                null,
                null,
                0,
                false,
                "{}",
                outputJson,
                "trace-pet",
                now,
                now,
                null,
                null
        );
    }

    private JsonNode stickerDraft() throws Exception {
        return objectMapper.readTree("""
                {
                  "templateId": "pet-sticker",
                  "prompt": "Create a cute pet chat sticker with a shy expression and one short overlay caption.",
                  "videoType": "sticker",
                  "generationMode": "reference_video",
                  "aspectRatio": "1:1",
                  "durationSeconds": 5,
                  "style": "funny",
                  "language": "zh-CN",
                  "voiceEnabled": false,
                  "lipSyncEnabled": false,
                  "subtitleEnabled": false,
                  "bgmEnabled": false,
                  "roles": [
                    {"id":"role-main","name":"Milo","type":"cat","personalityTags":["shy","cute"],"speakingTone":"soft"}
                  ],
                  "materials": [
                    {"id":"mat-1","role":"main_pet","assetId":"2698","url":"https://example.com/cat.png","label":"white cat reference"}
                  ],
                  "dialogueLines": [],
                  "scriptText": "Oops",
                  "subtitleStyle": {
                    "position": "bottom",
                    "highlighted": true,
                    "fontFamily": "Arial Black",
                    "fontSize": 34,
                    "textColor": "#ff3366",
                    "outlineColor": "#ffffff",
                    "strokeMode": "strong"
                  },
                  "visualSettings": {
                    "expressionIntensity": 92,
                    "cameraRhythm": "fast",
                    "backgroundPrompt": "",
                    "stickerOverlay": {
                      "text": "Oops",
                      "textX": 41,
                      "textY": 78,
                      "icon": "sparkle",
                      "iconX": 73,
                      "iconY": 19,
                      "staticFormat": "png",
                      "dynamicFormat": "gif"
                    }
                  },
                  "consistency": {
                    "keepAppearance": true,
                    "keepFurPattern": true,
                    "keepScene": false,
                    "allowAnthropomorphic": false,
                    "multiShotPriority": true
                  },
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":1,"frameDescription":"Square sticker framing with the pet face centered and clean background.","characterAction":"The pet looks shy and blinks once.","cameraMove":"locked close-up"},
                    {"id":"shot-2","index":2,"durationSeconds":2,"frameDescription":"Keep the same pet identity, fur color, face shape and safe overlay margin.","characterAction":"The pet makes a tiny paw raise and soft expression.","cameraMove":"very slight push in"},
                    {"id":"shot-3","index":3,"durationSeconds":2,"frameDescription":"Return to the opening pose for a loopable GIF sticker.","characterAction":"The pet settles into the same shy pose.","cameraMove":"locked close-up"}
                  ]
                }
                """);
    }

    private JsonNode multiPetDialogueDraft() throws Exception {
        return objectMapper.readTree("""
                {
                  "templateId": "multi-pet-dialogue",
                  "prompt": "Two pets talk in a warm living room with a simple funny turn.",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 10,
                  "style": "funny",
                  "language": "zh-CN",
                  "voiceEnabled": true,
                  "lipSyncEnabled": true,
                  "subtitleEnabled": true,
                  "bgmEnabled": true,
                  "roles": [
                    {"id":"role-main","name":"Milo","type":"cat","personalityTags":["cute"],"speakingTone":"soft child voice"},
                    {"id":"role-second","name":"Buddy","type":"dog","personalityTags":["smart"],"speakingTone":"quick young voice"}
                  ],
                  "materials": [
                    {"id":"mat-1","role":"main_pet","assetId":"2698","url":"https://example.com/cat.png","label":"main cat"},
                    {"id":"mat-2","role":"second_pet","assetId":"2665","url":"https://example.com/dog.png","label":"second dog"},
                    {"id":"mat-3","role":"scene","assetId":"2683","url":"https://example.com/living-room.png","label":"living room"}
                  ],
                  "dialogueLines": [
                    {"id":"line-1","speakerRoleId":"role-main","text":"I was just passing by.","emotion":"认真解释","speed":"normal","voiceName":"soft child voice","lipSync":true},
                    {"id":"line-2","speakerRoleId":"role-second","text":"Then why is the snack bag here?","emotion":"吐槽","speed":"normal","voiceName":"quick young voice","lipSync":true}
                  ],
                  "scriptText": "Cat explains, dog asks, cat gives a cute answer.",
                  "visualSettings": {
                    "expressionIntensity": 82,
                    "cameraRhythm": "short_drama",
                    "backgroundPrompt": "Warm clean indoor living room"
                  },
                  "consistency": {
                    "keepAppearance": true,
                    "keepFurPattern": true,
                    "keepScene": false,
                    "allowAnthropomorphic": false,
                    "multiShotPriority": true
                  },
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":3,"frameDescription":"Cat in foreground and dog separate in the living room.","characterAction":"Cat looks at camera, dog leans in.","cameraMove":"stable medium shot","subtitle":"I was just passing by."},
                    {"id":"shot-2","index":2,"durationSeconds":3,"frameDescription":"Both pets stay separate and clear.","characterAction":"Dog tilts head, cat blinks.","cameraMove":"slight follow shot","subtitle":"Then why is the snack bag here?"},
                    {"id":"shot-3","index":3,"durationSeconds":4,"frameDescription":"Close-up on the cat face, dog still separate.","characterAction":"Cat gives a shy tiny reaction.","cameraMove":"locked close-up","subtitle":"It walked here by itself."}
                  ]
                }
                """);
    }

    private JsonNode storyDemoDraft() throws Exception {
        return objectMapper.readTree("""
                {
                  "templateId": "pet-human-story-video",
                  "templateName": "人宠情景视频",
                  "title": "客厅里的零食会议",
                  "prompt": "林然在客厅发现小狗豆包和小猫栗子正在认真讨论零食分配，最后加入会议给出任务奖励。",
                  "videoType": "dialogue",
                  "generationMode": "dialogue_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 12,
                  "style": "realistic",
                  "language": "zh-CN",
                  "voiceEnabled": true,
                  "lipSyncEnabled": true,
                  "subtitleEnabled": true,
                  "bgmEnabled": true,
                  "story": {
                    "title": "客厅里的零食会议",
                    "summary": "主人林然发现豆包和栗子围在茶几旁开零食会议。",
                    "conflict": "两只宠物都想证明自己应该多拿一份小鱼干。",
                    "twist": "栗子把小鱼干藏在沙发垫下，豆包其实早就发现了。",
                    "ending": "林然把会议改成小游戏，完成握手和坐好后公平分零食。",
                    "scene": "明亮整洁的家庭客厅，茶几、沙发和宠物零食自然出现。",
                    "style": "真实、可爱、温暖、家庭感"
                  },
                  "characters": [
                    {"id":"role-human","name":"林然","type":"human","description":"温暖自然的年轻宠物主人"},
                    {"id":"role-main","name":"豆包","type":"dog","description":"清晰正脸的小狗，聪明但有点嘴馋"},
                    {"id":"role-second","name":"栗子","type":"cat","description":"小猫，机灵又会卖萌"}
                  ],
                  "humanAssets": [
                    {"avatarAssetId":"9001","displayName":"林然","roleName":"林然","visualDescription":"温暖自然的年轻宠物主人，居家浅色针织衫，友善表情","voiceProfileId":"owner-warm-female","subtitleStyleId":"pet-demo-bottom"}
                  ],
                  "roles": [
                    {"id":"role-human","name":"林然","type":"other","personalityTags":["温暖","耐心"],"speakingTone":"温柔自然"},
                    {"id":"role-main","name":"豆包","type":"dog","personalityTags":["机智","嘴馋"],"speakingTone":"机智少年音"},
                    {"id":"role-second","name":"栗子","type":"cat","personalityTags":["机灵","会卖萌"],"speakingTone":"软萌童声"}
                  ],
                  "materials": [
                    {"id":"mat-dog","role":"main_pet","assetId":"8001","url":"https://example.com/doubao.png","label":"豆包小狗主体参考"},
                    {"id":"mat-cat","role":"second_pet","assetId":"8002","url":"https://example.com/lizi.png","label":"栗子小猫主体参考"},
                    {"id":"mat-human","role":"human_avatar","assetId":"9001","url":"https://example.com/linran.png","label":"林然主人头像参考"},
                    {"id":"mat-scene","role":"scene","assetId":"8100","url":"https://example.com/living-room.png","label":"温暖客厅场景参考"}
                  ],
                  "dialogueLines": [
                    {"id":"line-1","speakerRoleId":"role-human","text":"你们两个，在开什么小会？","emotion":"好奇","speed":"normal","voiceName":"温柔自然女声","lipSync":true},
                    {"id":"line-2","speakerRoleId":"role-main","text":"我们在讨论零食公平分配。","emotion":"认真解释","speed":"normal","voiceName":"机智少年音","lipSync":true},
                    {"id":"line-3","speakerRoleId":"role-second","text":"我只是负责保管小鱼干。","emotion":"撒娇","speed":"normal","voiceName":"软萌童声","lipSync":true},
                    {"id":"line-4","speakerRoleId":"role-human","text":"那完成坐好和握手，再一起领奖励。","emotion":"开心","speed":"normal","voiceName":"温柔自然女声","lipSync":true}
                  ],
                  "scriptText": "林然走进客厅，看见豆包和栗子围着零食袋认真开会。豆包解释是在讨论公平分配，栗子卖萌说自己只是保管小鱼干。林然发现小鱼干藏在沙发垫下，把会议变成小游戏，两只宠物完成动作后一起得到奖励。",
                  "subtitleConfig": {"enabled":true,"language":"zh-CN","position":"bottom","fontFamily":"Microsoft YaHei","fontSize":34,"textColor":"#ffffff","strokeMode":"strong"},
                  "audioConfig": {"bgm":"warm-light-home","voiceMix":"dialogue-first","humanVoice":"owner-warm-female","dogVoice":"smart-young","catVoice":"soft-child"},
                  "visualSettings": {
                    "expressionIntensity": 74,
                    "cameraRhythm": "balanced",
                    "backgroundPrompt": "明亮整洁的室内客厅，暖色自然光，茶几和沙发清晰但不抢主体",
                    "productPrompt": "宠物零食袋和小鱼干作为小道具出现，不遮挡宠物脸部"
                  },
                  "consistency": {"keepAppearance":true,"keepFurPattern":true,"keepScene":true,"allowAnthropomorphic":false,"multiShotPriority":true},
                  "assetSections": {"storyboard":"分镜","subtitle":"文案","audio":"音频","finishedVideo":"视频"},
                  "negativePrompt": ["不要生成无剧情视频", "不要复用汽车创作中心模板内容"],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":4,"frameDescription":"林然走进温暖客厅，看见豆包和栗子围在茶几旁，零食袋在旁边。","characterAction":"林然轻轻弯腰看向两只宠物，豆包抬头，栗子坐在沙发边。","cameraMove":"稳定中景轻微推近","subtitle":"你们两个，在开什么小会？"},
                    {"id":"shot-2","index":2,"durationSeconds":4,"frameDescription":"豆包和栗子分别在茶几两侧，保持清晰分离，零食袋和沙发垫可见。","characterAction":"豆包认真看镜头，栗子轻轻眨眼卖萌，林然在旁边听。","cameraMove":"固定中近景","subtitle":"我们在讨论零食公平分配。"},
                    {"id":"shot-3","index":3,"durationSeconds":4,"frameDescription":"林然发现沙发垫下的小鱼干，最后两只宠物坐好等待奖励。","characterAction":"林然拿起小鱼干笑着示意，豆包坐好，栗子抬头等待。","cameraMove":"轻微推近后稳定收尾","subtitle":"完成坐好和握手，再一起领奖励。"}
                  ]
                }
                """);
    }
}
