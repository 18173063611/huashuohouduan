package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import com.huashuo.petvideo.dto.PetWorkForkRequest;
import com.huashuo.petvideo.dto.PetVideoPreviewResponse;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.dto.PetWorkResponse;
import com.huashuo.petvideo.entity.PetVideoWorkEntity;
import com.huashuo.petvideo.mapper.PetVideoWorkMapper;
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
                "seedance-text-model",
                "seedance-reference-model"
        );
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
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["嘴硬"],"speakingTone":"软萌"}],
                  "materials": [{"id":"mat-1","role":"main_pet","url":"https://example.com/cat.png","label":"主宠"}],
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
        assertEquals("pet-video-prompt-v2", requestCaptor.getValue().getDiagnosticMetadata().get("promptVersion").asText());
        assertTrue(requestCaptor.getValue().getDiagnosticMetadata().has("draftSnapshot"));
        assertTrue(requestCaptor.getValue().getDiagnosticMetadata().has("materialSummary"));
        assertTrue(requestCaptor.getValue().getDiagnosticMetadata().has("shotSummary"));

        ArgumentCaptor<PetVideoWorkEntity> workCaptor = ArgumentCaptor.forClass(PetVideoWorkEntity.class);
        verify(workMapper).insert(workCaptor.capture());
        assertEquals(7L, workCaptor.getValue().getOwnerUserId());
        assertEquals(100L, workCaptor.getValue().getTaskId());
        assertEquals("cat", workCaptor.getValue().getPetType());
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
                null,
                "trace-pet",
                now,
                now,
                null,
                null
        );
    }
}
