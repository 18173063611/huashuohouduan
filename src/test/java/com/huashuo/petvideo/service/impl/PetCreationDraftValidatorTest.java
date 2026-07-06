package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetCreationDraftValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PetCreationDraftValidator validator = new PetCreationDraftValidator(objectMapper);

    @Test
    void rejectsMissingPrompt() throws Exception {
        ObjectNode draft = validDraft();
        draft.put("prompt", "");

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("创作需求"));
    }

    @Test
    void rejectsMissingMainPetInReferenceMode() throws Exception {
        ObjectNode draft = validDraft();
        draft.set("materials", objectMapper.createArrayNode());

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("主宠物参考图"));
    }

    @Test
    void rejectsLipSyncWithoutDialogue() throws Exception {
        ObjectNode draft = validDraft();
        draft.put("lipSyncEnabled", true);
        draft.set("dialogueLines", objectMapper.createArrayNode());

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("有效台词"));
    }

    @Test
    void rejectsEmptyStoryboard() throws Exception {
        ObjectNode draft = validDraft();
        draft.set("shots", objectMapper.createArrayNode());

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("分镜"));
    }

    @Test
    void rejectsTooManyStoryboardShots() throws Exception {
        ObjectNode draft = validDraft();
        var shots = objectMapper.createArrayNode();
        for (int i = 1; i <= 9; i++) {
            shots.add(objectMapper.readTree("""
                    {"id":"shot-x","index":1,"durationSeconds":2,"frameDescription":"小猫在客厅","characterAction":"小猫眨眼","cameraMove":"固定近景","subtitle":"你好"}
                    """));
        }
        draft.set("shots", shots);

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("最多 8 个"));
    }

    @Test
    void rejectsEmptyMaterialLocator() throws Exception {
        ObjectNode draft = validDraft();
        draft.set("materials", objectMapper.readTree("""
                [{"id":"mat-1","role":"main_pet","url":"","label":"主宠"}]
                """));

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("主宠物参考图") || ex.getMessage().contains("缺少 URL"));
    }

    @Test
    void rejectsTooLongBackgroundPrompt() throws Exception {
        ObjectNode draft = validDraft();
        draft.set("visualSettings", objectMapper.readTree("""
                {"backgroundPrompt":"这是一个非常长的背景要求，用来验证宠物背景图编辑字段不会无限写入 provider prompt，避免把无关文本塞进生成任务造成不可控结果。这段文字会继续重复，这是一个非常长的背景要求，用来验证宠物背景图编辑字段不会无限写入 provider prompt，避免把无关文本塞进生成任务造成不可控结果。继续补充更多背景要求，要求灯光、景深、空间、构图、色彩、主体位置、道具位置全部被描述得过于冗长。"}
                """));

        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validateForTask(draft));

        assertTrue(ex.getMessage().contains("背景图/场景要求"));
    }

    private ObjectNode validDraft() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "prompt": "小猫在客厅认真解释自己为什么偷吃零食",
                  "videoType": "monologue",
                  "generationMode": "reference_video",
                  "aspectRatio": "9:16",
                  "durationSeconds": 15,
                  "style": "cute",
                  "voiceEnabled": true,
                  "lipSyncEnabled": false,
                  "subtitleEnabled": true,
                  "roles": [{"id":"role-main","name":"奶油","type":"cat","personalityTags":["嘴硬"],"speakingTone":"软萌"}],
                  "materials": [{"id":"mat-1","role":"main_pet","url":"https://example.com/cat.png","label":"主宠"}],
                  "dialogueLines": [{"id":"line-1","speakerRoleId":"role-main","text":"我只是闻了一下","emotion":"委屈","speed":"normal","voiceName":"cute","lipSync":false}],
                  "shots": [
                    {"id":"shot-1","index":1,"durationSeconds":5,"frameDescription":"小猫坐在客厅地毯上","characterAction":"小猫眨眼","cameraMove":"稳定推近","subtitle":"我只是闻了一下"},
                    {"id":"shot-2","index":2,"durationSeconds":5,"frameDescription":"镜头靠近零食袋","characterAction":"小猫假装无辜","cameraMove":"轻微跟拍","subtitle":"真的没有偷吃"},
                    {"id":"shot-3","index":3,"durationSeconds":5,"frameDescription":"小猫歪头收尾","characterAction":"小猫撒娇","cameraMove":"固定近景","subtitle":"下次分你一点"}
                  ]
                }
                """);
    }
}
