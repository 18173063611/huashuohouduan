package com.huashuo.writer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserCreditAccountMapper;
import com.huashuo.user.mapper.UserCreditLogMapper;
import com.huashuo.writer.dto.VideoScriptSubmitRequest;
import com.huashuo.writer.service.WriterAsyncTaskService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分镜任务：HTTP 鉴权、owner_user_id 落库、预扣流水，以及 createTask 对「有费用无用户」的前置拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "itest"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VideoScriptAuthAndOwnerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WriterAsyncTaskService writerAsyncTaskService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserCreditAccountMapper userCreditAccountMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Test
    @Order(1)
    void scriptAnalyzeEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/video/script/analy").param("url", "https://example.com/v.mp4"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void scriptUrlEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/video/script/url").param("url", "https://v.douyin.com/abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void createVideoScriptAnalyzeTask_persistsOwnerAndPrecharge() {
        long userId = insertUserWithBalance(100_000L);
        String idem = "ITEST:SCRIPT:FILE:" + UUID.randomUUID();
        TaskItem t = writerAsyncTaskService.createVideoScriptAnalyzeTask(
                new VideoScriptSubmitRequest("https://example.com/vid.mp4"),
                userId,
                null,
                "trace-itest-file",
                idem);
        TaskEntity row = taskMapper.selectById(t.taskId());
        assertNotNull(row.getOwnerUserId());
        assertEquals(userId, row.getOwnerUserId());
        assertNotNull(row.getEstimatedCreditCost());
        assertTrue(row.getEstimatedCreditCost() > 0);
        long consumeLogs = userCreditLogMapper.selectCount(
                new LambdaQueryWrapper<UserCreditLogEntity>()
                        .eq(UserCreditLogEntity::getRelatedTaskId, t.taskId())
                        .eq(UserCreditLogEntity::getChangeType, "AI_CONSUME"));
        assertEquals(1, consumeLogs);
    }

    @Test
    @Order(4)
    void createVideoScriptUrlAnalyzeTask_persistsOwnerAndPrecharge() {
        long userId = insertUserWithBalance(100_000L);
        String idem = "ITEST:SCRIPT:URL:" + UUID.randomUUID();
        TaskItem t = writerAsyncTaskService.createVideoScriptUrlAnalyzeTask(
                new VideoScriptSubmitRequest("https://v.douyin.com/abc"),
                userId,
                null,
                "trace-itest-url",
                idem);
        TaskEntity row = taskMapper.selectById(t.taskId());
        assertNotNull(row.getOwnerUserId());
        assertEquals(userId, row.getOwnerUserId());
        assertNotNull(row.getEstimatedCreditCost());
        assertTrue(row.getEstimatedCreditCost() > 0);
        long consumeLogs = userCreditLogMapper.selectCount(
                new LambdaQueryWrapper<UserCreditLogEntity>()
                        .eq(UserCreditLogEntity::getRelatedTaskId, t.taskId())
                        .eq(UserCreditLogEntity::getChangeType, "AI_CONSUME"));
        assertEquals(1, consumeLogs);
    }

    @Test
    @Order(5)
    void createTask_paidVideoScript_nullOwner_rejectedBeforePersist() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                taskService.createTask(
                        null,
                        TaskTypeCode.VIDEO_SCRIPT_ANALYZE,
                        "{\"url\":\"https://example.com/x.mp4\"}",
                        "trace-null-owner",
                        null,
                        null,
                        null,
                        "ITEST:NULL-OWNER:" + UUID.randomUUID()));
        assertEquals(40100, ex.getCode());
        assertTrue(ex.getMessage().contains("缺少登录"));
    }

    private long insertUserWithBalance(long balance) {
        UserAccountEntity user = new UserAccountEntity();
        user.setUsername("itest-script-" + UUID.randomUUID());
        user.setPasswordHash("$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG");
        user.setDisplayName("itest-script");
        user.setRole("USER");
        user.setStatus("ENABLED");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.insert(user);

        UserCreditAccountEntity account = new UserCreditAccountEntity();
        account.setUserId(user.getUserId());
        account.setBalance(balance);
        account.setFrozenBalance(0L);
        account.setTotalRecharged(balance);
        account.setTotalConsumed(0L);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        userCreditAccountMapper.insert(account);
        return user.getUserId();
    }
}
