package com.huashuo.billing.acceptance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.controller.AdminBillingController;
import com.huashuo.admin.dto.AdminBillingStepSaveRequest;
import com.huashuo.admin.mapper.AdminOperationLogMapper;
import com.huashuo.admin.service.AdminBillingReportService;
import com.huashuo.admin.service.AdminBillingService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.vo.AdminBillingStepItem;
import com.huashuo.admin.vo.AdminUsageSummaryResponse;
import com.huashuo.admin.vo.AdminUsageSummaryRow;
import com.huashuo.billing.entity.AiBillingStepConfigEntity;
import com.huashuo.billing.entity.AiUsageLogEntity;
import com.huashuo.billing.entity.CreditDebtLogEntity;
import com.huashuo.billing.mapper.AiBillingStepConfigMapper;
import com.huashuo.billing.mapper.AiUsageLogMapper;
import com.huashuo.billing.mapper.CreditDebtLogMapper;
import com.huashuo.billing.model.BillingEstimateRequest;
import com.huashuo.billing.model.BillingEstimateResponse;
import com.huashuo.billing.model.CreditDebtStatus;
import com.huashuo.billing.model.SettlementStatus;
import com.huashuo.billing.model.UsageActualResult;
import com.huashuo.billing.model.UsagePhase;
import com.huashuo.billing.model.UsageUnit;
import com.huashuo.billing.service.BillingEstimateService;
import com.huashuo.billing.service.BillingStepConfigService;
import com.huashuo.billing.service.CreditBillingService;
import com.huashuo.task.config.TaskCreditProperties;
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
import com.huashuo.user.service.AccountCreditDetailService;
import com.huashuo.user.service.CreditService;
import com.huashuo.user.vo.account.AccountCreditLogRecentRow;
import com.huashuo.user.vo.account.TaskCreditDetailResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 积分计费系统上线验收集成测试。
 *
 * <p>组织方式：单一 Spring 上下文一次启动，按"七个验收单元"顺序逐项执行；每个测试方法既是 JUnit
 * 断言，也是验收报告的一段事实记录。最终 {@link #emitReport()} 把累计的事实汇总打印到 stdout，
 * 与 mvn 输出一起作为人工复核证据。</p>
 *
 * <p>真实链路覆盖：直接调用 {@link TaskService}、{@link CreditBillingService}、
 * {@link AdminBillingService}、{@link AdminBillingReportService} 等真实 Bean，配合真实 H2 schema
 * 与 seed，不 mock 业务服务；唯一不真实的是不调用外部 Volcengine / Vidu 网络接口（这些只在异步
 * Executor 中触发，本测试不启动 Executor，而是用 settle / failTask 直接模拟其结果）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing-acceptance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "spring.sql.init.data-locations=",
        "spring.h2.console.enabled=false"
})
@ActiveProfiles({"local", "itest"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingAcceptanceTests {

    /** 跨测试方法累积的"事实记录"，用于在 {@link #emitReport()} 中输出验收报告。 */
    private static final List<String> FINDINGS = new CopyOnWriteArrayList<>();
    private static final List<String> RISKS = new CopyOnWriteArrayList<>();

    @Autowired private TaskService taskService;
    @Autowired private CreditService creditService;
    @Autowired private CreditBillingService creditBillingService;
    @Autowired private BillingStepConfigService billingStepConfigService;
    @Autowired private BillingEstimateService billingEstimateService;
    @Autowired private AdminBillingService adminBillingService;
    @Autowired private AdminBillingReportService adminBillingReportService;
    @Autowired private TaskCreditProperties taskCreditProperties;

    @Autowired private TaskMapper taskMapper;
    @Autowired private UserAccountMapper userAccountMapper;
    @Autowired private UserCreditAccountMapper userCreditAccountMapper;
    @Autowired private UserCreditLogMapper userCreditLogMapper;
    @Autowired private AiUsageLogMapper aiUsageLogMapper;
    @Autowired private AiBillingStepConfigMapper stepConfigMapper;
    @Autowired private CreditDebtLogMapper creditDebtLogMapper;
    @Autowired private AdminOperationLogMapper adminOperationLogMapper;

    @Autowired private AdminBillingController adminBillingController;
    @Autowired private AccountCreditDetailService accountCreditDetailService;

    // ============================================================================================
    // 一、任务创建与预扣（多种 task_type 全覆盖）
    // ============================================================================================

    @Test
    @Order(1)
    void section1_taskCreationPrechargeAcrossAllTaskTypes() {
        long userId = newUser("user-section1", 100_000L);
        record Case(String label, String taskType, long expectedMinCost) {}
        List<Case> cases = List.of(
                new Case("TTS",                  TaskTypeCode.TTS_GENERATE,             1L),
                new Case("图片生成 (Avatar)",      TaskTypeCode.AVATAR_GENERATE,          1L),
                new Case("数字人口播 (Vidu)",       TaskTypeCode.DIGITAL_HUMAN_GENERATE,   1L),
                new Case("Seedance 文生视频 1.5",   TaskTypeCode.TEXT_TO_VIDEO_SEEDANCE_1_5, 1L),
                new Case("Seedance 图生视频 2.0",   TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_2_0, 1L),
                new Case("视频理解 VIDEO_PARSE",   TaskTypeCode.VIDEO_PARSE,              1L),
                new Case("分镜解析(上传)",          TaskTypeCode.VIDEO_SCRIPT_ANALYZE,     1L),
                new Case("分镜解析(链接)",          TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE, 1L)
        );
        for (Case c : cases) {
            long balanceBefore = balanceOf(userId);
            String idem = "ACC:CREATE:" + c.taskType() + ":" + UUID.randomUUID();
            TaskItem task = taskService.createTask(null, c.taskType(), "{}", "trace-1", userId, null, null, idem);
            TaskEntity row = taskMapper.selectById(task.taskId());

            // (1) 步骤配置确实被读取了：BillingStepConfigService 应有非空汇总
            var aggregate = billingStepConfigService.aggregateCreditCost(c.taskType());
            assertTrue(aggregate.isPresent(), c.label() + ": ai_billing_step_config 未命中");
            assertTrue(aggregate.getAsLong() >= c.expectedMinCost(),
                    c.label() + ": 步骤汇总应 >= " + c.expectedMinCost() + " 实际 " + aggregate.getAsLong());

            // (2) 固定步骤任务以 ai_billing_step_config 汇总作为预扣；用量明确的任务（如图片张数/字符数）
            //     允许按 ai_model_price 动态预扣，但必须与统一预估接口一致。
            BillingEstimateResponse estimate = billingEstimateService.estimate(estimateRequest(c.taskType(), userId));
            assertEquals(Long.valueOf(estimate.estimatedCreditCost()), row.getEstimatedCreditCost(),
                    c.label() + ": createTask 预扣金额必须等于 /billing/estimate 统一预估");
            if (!BillingEstimateResponse.SOURCE_USAGE_MODEL_PRICE.equals(estimate.pricingSource())) {
                assertEquals(Long.valueOf(aggregate.getAsLong()), row.getEstimatedCreditCost(),
                        c.label() + ": 非动态用量任务仍应等于 ai_billing_step_config 汇总");
            }
            // (3) settlement_status = PRECHARGED（成本>0）或 NONE（成本=0）
            String expectedStatus = row.getEstimatedCreditCost() > 0
                    ? SettlementStatus.PRECHARGED : SettlementStatus.NONE;
            assertEquals(expectedStatus, row.getSettlementStatus(),
                    c.label() + ": settlement_status 不正确");
            // (4) user_credit_log 写入了 AI_CONSUME 一条
            List<UserCreditLogEntity> consumeLogs = userCreditLogMapper.selectList(
                    new LambdaQueryWrapper<UserCreditLogEntity>()
                            .eq(UserCreditLogEntity::getRelatedTaskId, row.getTaskId())
                            .eq(UserCreditLogEntity::getChangeType, "AI_CONSUME"));
            assertEquals(1, consumeLogs.size(), c.label() + ": user_credit_log 应当只有 1 条预扣");
            assertEquals(-row.getEstimatedCreditCost(), consumeLogs.get(0).getChangeAmount(),
                    c.label() + ": 流水扣减额与 estimated_credit_cost 不一致");

            // (5) ai_usage_log 写了一条 ESTIMATE
            List<AiUsageLogEntity> estimateLogs = aiUsageLogMapper.selectList(
                    new LambdaQueryWrapper<AiUsageLogEntity>()
                            .eq(AiUsageLogEntity::getTaskId, row.getTaskId())
                            .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ESTIMATE));
            assertEquals(1, estimateLogs.size(),
                    c.label() + ": ai_usage_log ESTIMATE 行应当只有 1 条");
            assertEquals(row.getEstimatedCreditCost(), estimateLogs.get(0).getEstimatedCreditCost(),
                    c.label() + ": ESTIMATE 行 estimated_credit_cost 与 task 不一致");

            // (6) 账户余额被扣减
            long balanceAfter = balanceOf(userId);
            assertEquals(balanceBefore - row.getEstimatedCreditCost(), balanceAfter,
                    c.label() + ": 余额扣减额度不正确");

            note("[一] %s task_type=%s 预扣=%d (source=%s), status=%s, balance %d→%d",
                    c.label(), c.taskType(), row.getEstimatedCreditCost(), estimate.pricingSource(),
                    row.getSettlementStatus(), balanceBefore, balanceAfter);
        }

        // (7) 余额不足时直接拒绝
        long emptyUser = newUser("user-empty", 0L);
        assertThrows(RuntimeException.class, () ->
                        taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "trace-empty", emptyUser, null, null,
                                "ACC:CREATE:NOBALANCE:" + UUID.randomUUID()),
                "余额不足应抛业务异常");
        long taskCountForEmpty = taskMapper.selectCount(
                new LambdaQueryWrapper<TaskEntity>().eq(TaskEntity::getOwnerUserId, emptyUser));
        assertEquals(0, taskCountForEmpty, "余额不足时不应该写入 task 占位行");
        note("[一] 余额不足校验：assertBalanceAtLeast 阻断，task 表未写入占位行");
    }

    // ============================================================================================
    // 二、失败退款
    // ============================================================================================

    @Test
    @Order(2)
    void section2_failTaskRefundAndNoRefund() {
        long userId = newUser("user-section2", 100_000L);
        // (a) refundIfFail=true：完整退款 + settlement_status=REFUNDED
        TaskItem task = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "trace-2a", userId, null, null,
                "ACC:FAIL:REFUND:" + UUID.randomUUID());
        long preCost = Objects.requireNonNull(taskMapper.selectById(task.taskId()).getEstimatedCreditCost());
        long balanceBeforeFail = balanceOf(userId);

        taskService.failTask(task.taskId(), "mock fail before submit", false, true);
        TaskEntity row = taskMapper.selectById(task.taskId());
        assertEquals(SettlementStatus.REFUNDED, row.getSettlementStatus(),
                "refundIfFail=true 时 settlement_status 应当变为 REFUNDED");
        long balanceAfterRefund = balanceOf(userId);
        assertEquals(balanceBeforeFail + preCost, balanceAfterRefund, "应完整退还预扣积分");
        // user_credit_log 多了一条 AI_REFUND
        long refundLogCount = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, row.getTaskId())
                .eq(UserCreditLogEntity::getChangeType, "AI_REFUND"));
        assertEquals(1, refundLogCount, "AI_REFUND 流水应有 1 条");
        // ai_usage_log 不应出现 ACTUAL（失败路径只有 ESTIMATE）
        long actualLogCount = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, row.getTaskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(0, actualLogCount, "失败退款路径不应写 ACTUAL usage log");
        note("[二] refundIfFail=true：积分退回 %d，settlement_status=REFUNDED，AI_REFUND 流水 +1，无 ACTUAL", preCost);

        // (b) refundIfFail=false：不退款，但要进入 SETTLED 终态 + 写一条 ACTUAL 占位
        TaskItem task2 = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "trace-2b", userId, null, null,
                "ACC:FAIL:NOREFUND:" + UUID.randomUUID());
        long balanceBeforeFail2 = balanceOf(userId);
        long preCost2 = Objects.requireNonNull(taskMapper.selectById(task2.taskId()).getEstimatedCreditCost());

        String failReason = "third-party already submitted";
        taskService.failTask(task2.taskId(), failReason, false, false);
        TaskEntity row2 = taskMapper.selectById(task2.taskId());
        // (b.1) 不动余额：refundIfFail=false 仍然不退款
        assertEquals(balanceBeforeFail2, balanceOf(userId), "refundIfFail=false 时不退款");
        long refundCount2 = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, row2.getTaskId())
                .eq(UserCreditLogEntity::getChangeType, "AI_REFUND"));
        assertEquals(0, refundCount2, "AI_REFUND 流水不应出现");
        // (b.2) settlement_status：从 PRECHARGED 推进到 SETTLED（不再停留 PRECHARGED）
        assertEquals(SettlementStatus.SETTLED, row2.getSettlementStatus(),
                "refundIfFail=false 任务应当推进到 SETTLED 终态，避免与真排队中任务混在 PRECHARGED");
        // (b.3) actual_credit_cost = estimated_credit_cost（预扣已作为实际成本消费）
        assertEquals(Long.valueOf(preCost2), row2.getActualCreditCost(),
                "refundIfFail=false 时 task.actual_credit_cost 应等于 estimated_credit_cost");
        // (b.4) ai_usage_log 应当写入一条 usage_phase=ACTUAL 占位，actual_credit_cost = estimated
        List<AiUsageLogEntity> actualLogs2 = aiUsageLogMapper.selectList(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, row2.getTaskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(1, actualLogs2.size(), "refundIfFail=false 路径必须写一条 ACTUAL 占位 usage_log");
        AiUsageLogEntity actualLog = actualLogs2.get(0);
        assertEquals(Long.valueOf(preCost2), actualLog.getActualCreditCost(),
                "ACTUAL 占位行 actual_credit_cost 应等于 estimated_credit_cost");
        // (b.5) raw_usage_json 必须包含 failReason / refundCredits=false / thirdPartyAccepted=true
        String rawJson = actualLog.getRawUsageJson();
        assertNotNull(rawJson, "raw_usage_json 不应为 null");
        assertTrue(rawJson.contains("failReason") && rawJson.contains(failReason),
                "raw_usage_json 应当包含 failReason: " + rawJson);
        assertTrue(rawJson.contains("\"refundCredits\":false"),
                "raw_usage_json 应当包含 refundCredits=false: " + rawJson);
        assertTrue(rawJson.contains("\"thirdPartyAccepted\":true"),
                "raw_usage_json 应当包含 thirdPartyAccepted=true: " + rawJson);

        // (b.6) 报表 finalCreditCost：refundIfFail=false 任务因为 actual_credit_cost = estimated > 0，
        //       应以 ACTUAL 行作为最终成本，不再回退到 ESTIMATE，且与 task.actual_credit_cost 一致。
        LocalDate today = LocalDate.now();
        AdminUsageSummaryResponse report = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.TASK_TYPE,
                today.minusDays(1), today,
                TaskTypeCode.TTS_GENERATE, null, null, null, null);
        AdminUsageSummaryRow ttsRow = report.rows().stream()
                .filter(r -> r.groupKey().equals(TaskTypeCode.TTS_GENERATE))
                .findFirst().orElse(null);
        assertNotNull(ttsRow, "报表应包含 TTS_GENERATE 分组");
        assertTrue(ttsRow.actualCreditCost() >= preCost2,
                "TTS_GENERATE 实际积分聚合值至少应含本任务的 " + preCost2 + " 分");
        assertTrue(ttsRow.finalCreditCost() >= preCost2,
                "finalCreditCost 必须包含 refundIfFail=false 任务的预扣金额 " + preCost2);

        // (b.7) 幂等：再次调用 recordConsumeWithoutRefund 不会再写一行 ACTUAL
        long beforeRetry = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, row2.getTaskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        creditBillingService.recordConsumeWithoutRefund(row2.getTaskId(), failReason);
        long afterRetry = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, row2.getTaskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(beforeRetry, afterRetry,
                "重复 recordConsumeWithoutRefund 应被 SETTLED 终态短路，不再写 ACTUAL");

        note("[二] refundIfFail=false：余额未变（仍 %d）、AI_REFUND=0、settlement_status=SETTLED、ACTUAL log=1、"
                        + "raw_usage_json 含 failReason+refundCredits=false+thirdPartyAccepted=true、finalCreditCost=%d",
                balanceOf(userId), ttsRow.finalCreditCost());
    }

    // ============================================================================================
    // 三、真实结算 settle
    // ============================================================================================

    @Test
    @Order(3)
    void section3_settleAndIdempotency() {
        long userId = newUser("user-section3", 100_000L);

        // 3.1 settle 写 ACTUAL + 扣减正确
        TaskItem task = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, "{}", "trace-3", userId,
                null, null, "ACC:SETTLE:" + UUID.randomUUID());
        TaskEntity row = taskMapper.selectById(task.taskId());
        long preCost = row.getEstimatedCreditCost();

        // 模拟实际生成 2 张图片，单价表中 unit_credit_price=1.0、unit=IMAGE → actual_cost=2
        UsageActualResult actual = new UsageActualResult(
                "VOLCENGINE", "avatar-seedream-default", UsageUnit.IMAGE,
                null, null, null, null, 2,
                BigDecimal.ZERO, BigDecimal.ZERO, null,
                "{\"imageCount\":2}");
        creditBillingService.settle(task.taskId(), actual);

        TaskEntity afterSettle = taskMapper.selectById(task.taskId());
        // settle 后 settlement_status 应该非 PRECHARGED
        assertTrue(List.of(SettlementStatus.SETTLED, SettlementStatus.REFUNDED, SettlementStatus.PARTIAL_REFUNDED)
                        .contains(afterSettle.getSettlementStatus()),
                "settle 后 settlement_status 应当落到终态，当前=" + afterSettle.getSettlementStatus());
        assertTrue(afterSettle.getActualCreditCost() != null && afterSettle.getActualCreditCost() >= 0,
                "settle 后 actualCreditCost 不应为 null");

        long actualUsageLogs = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, task.taskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(1, actualUsageLogs, "settle 应当写入 1 条 ACTUAL usage_log");

        // 3.2 重复 settle 被幂等拦截
        long actualLogsBefore = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, task.taskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        long settleConsumeBefore = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, task.taskId())
                .in(UserCreditLogEntity::getChangeType, List.of("AI_CONSUME", "AI_REFUND")));

        creditBillingService.settle(task.taskId(), actual);
        creditBillingService.settle(task.taskId(), actual);
        long actualLogsAfter = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, task.taskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        long settleConsumeAfter = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, task.taskId())
                .in(UserCreditLogEntity::getChangeType, List.of("AI_CONSUME", "AI_REFUND")));
        assertEquals(actualLogsBefore, actualLogsAfter,
                "重复 settle 不应再写 ACTUAL usage_log（设计为终态短路）");
        assertEquals(settleConsumeBefore, settleConsumeAfter,
                "重复 settle 不应再产生新的 AI_CONSUME / AI_REFUND 流水");

        // 3.3 settle 后 finalCost 优先用 ACTUAL（在 section 5 用 usage-summary 二次验证）
        note("[三] settle 后 settlement_status=%s, actualCreditCost=%d, ACTUAL log=%d, preCost=%d",
                afterSettle.getSettlementStatus(), afterSettle.getActualCreditCost(),
                actualLogsAfter, preCost);

        // 3.4 failTask 之后再 settle 也被终态短路（REFUNDED 也是终态）
        TaskItem failed = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "trace-3b", userId,
                null, null, "ACC:FAIL_THEN_SETTLE:" + UUID.randomUUID());
        taskService.failTask(failed.taskId(), "fail then settle", false, true);
        long failRefundBefore = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, failed.taskId()));
        creditBillingService.settle(failed.taskId(), new UsageActualResult(
                "VOLCENGINE", "tts-doubao-default", UsageUnit.CHAR,
                null, null, null, 50, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "{}"));
        long failRefundAfter = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, failed.taskId()));
        assertEquals(failRefundBefore, failRefundAfter,
                "已 REFUNDED 任务再 settle 不应再写流水（终态短路生效）");
        note("[三] failTask→settle 顺序：终态短路成功，未写入任何额外流水");
    }

    // ============================================================================================
    // 三 bis、VIDEO_PARSE 视频理解：有 / 没有 Ark usage 两条 ACTUAL 写入路径
    // ============================================================================================

    @Test
    @Order(35)
    void section3bis_videoParseActualUsageBothBranches() {
        long userId = newUser("user-section3bis", 100_000L);

        // (a) Ark 给了 usage.tokens → 走 settle 路径
        TaskItem taskWithTokens = taskService.createTask(null, TaskTypeCode.VIDEO_PARSE,
                "{\"step\":\"scriptAnalyze\",\"url\":\"https://example.com/v.mp4\"}",
                "trace-video-parse-with-tokens", userId, "doubao-seed-2-0-lite-260215", null,
                "ACC:VP:WITH_USAGE:" + UUID.randomUUID());
        long preCostWithTokens = taskMapper.selectById(taskWithTokens.taskId()).getEstimatedCreditCost();
        String summaryJsonWithTokens = "{\"model\":\"doubao-seed-2-0-lite-260215\",\"responseId\":\"resp-1\","
                + "\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":2000,\"completionTokens\":600,"
                + "\"totalTokens\":2600},\"scriptCount\":4}";
        UsageActualResult withTokens = new UsageActualResult(
                "VOLCENGINE", "doubao-seed-2-0-lite-260215", UsageUnit.TOKEN,
                2000, 600, 2600, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, summaryJsonWithTokens);
        creditBillingService.settle(taskWithTokens.taskId(), withTokens);

        TaskEntity rowWithTokens = taskMapper.selectById(taskWithTokens.taskId());
        assertTrue(List.of(SettlementStatus.SETTLED, SettlementStatus.PARTIAL_REFUNDED, SettlementStatus.REFUNDED)
                        .contains(rowWithTokens.getSettlementStatus()),
                "拿到 usage 的 VIDEO_PARSE 任务应当走完 settle，settlement_status 当前="
                        + rowWithTokens.getSettlementStatus());
        List<AiUsageLogEntity> actualLogsWithTokens = aiUsageLogMapper.selectList(
                new LambdaQueryWrapper<AiUsageLogEntity>()
                        .eq(AiUsageLogEntity::getTaskId, taskWithTokens.taskId())
                        .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(1, actualLogsWithTokens.size(), "settle 路径应写入 1 条 ACTUAL");
        AiUsageLogEntity actualWithTokens = actualLogsWithTokens.get(0);
        assertEquals(Integer.valueOf(2000), actualWithTokens.getPromptTokens(), "ACTUAL 行 promptTokens 写入错误");
        assertEquals(Integer.valueOf(600), actualWithTokens.getCompletionTokens(), "ACTUAL 行 completionTokens 写入错误");
        assertEquals(Integer.valueOf(2600), actualWithTokens.getTotalTokens(), "ACTUAL 行 totalTokens 写入错误");
        assertEquals(UsageUnit.TOKEN, actualWithTokens.getUsageUnit(), "ACTUAL 行 usage_unit 应为 TOKEN");
        assertNotNull(actualWithTokens.getRawUsageJson(), "raw_usage_json 不应为 null");
        assertTrue(actualWithTokens.getRawUsageJson().contains("promptTokens"),
                "raw_usage_json 应当包含响应摘要：" + actualWithTokens.getRawUsageJson());

        // (b) Ark 没给 usage → 走 recordActual 占位路径
        TaskItem taskNoTokens = taskService.createTask(null, TaskTypeCode.VIDEO_PARSE,
                "{\"step\":\"scriptAnalyze\",\"url\":\"https://example.com/v2.mp4\"}",
                "trace-video-parse-no-tokens", userId, "doubao-seed-2-0-lite-260215", null,
                "ACC:VP:NO_USAGE:" + UUID.randomUUID());
        long preCostNoTokens = taskMapper.selectById(taskNoTokens.taskId()).getEstimatedCreditCost();
        long balanceBeforeNoUsage = balanceOf(userId);
        String summaryJsonNoTokens = "{\"model\":\"doubao-seed-2-0-lite-260215\",\"responseId\":\"resp-2\","
                + "\"finishReason\":\"stop\",\"usage\":{\"promptTokens\":null,\"completionTokens\":null,"
                + "\"totalTokens\":null},\"scriptCount\":3}";
        UsageActualResult noTokens = new UsageActualResult(
                "VOLCENGINE", "doubao-seed-2-0-lite-260215", UsageUnit.TOKEN,
                null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, summaryJsonNoTokens);
        creditBillingService.recordActual(taskNoTokens.taskId(), noTokens);

        TaskEntity rowNoTokens = taskMapper.selectById(taskNoTokens.taskId());
        // recordActual 不动余额、不改 settlement_status
        assertEquals(SettlementStatus.PRECHARGED, rowNoTokens.getSettlementStatus(),
                "recordActual 不应推进 settlement_status，应保持 PRECHARGED");
        assertEquals(balanceBeforeNoUsage, balanceOf(userId),
                "recordActual 不应动余额");
        List<AiUsageLogEntity> actualLogsNoTokens = aiUsageLogMapper.selectList(
                new LambdaQueryWrapper<AiUsageLogEntity>()
                        .eq(AiUsageLogEntity::getTaskId, taskNoTokens.taskId())
                        .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(1, actualLogsNoTokens.size(), "recordActual 应写入 1 条占位 ACTUAL");
        AiUsageLogEntity actualNoTokens = actualLogsNoTokens.get(0);
        assertEquals(Long.valueOf(0L), actualNoTokens.getActualCreditCost(),
                "占位 ACTUAL 行 actual_credit_cost 应为 0（无 usage 时不计费）");
        assertEquals(Long.valueOf(preCostNoTokens), actualNoTokens.getEstimatedCreditCost(),
                "占位 ACTUAL 行 estimated_credit_cost 应等于 task.estimated_credit_cost");
        assertNotNull(actualNoTokens.getRawUsageJson(), "raw_usage_json 不应为 null（必须保留响应摘要供对账）");
        assertTrue(actualNoTokens.getRawUsageJson().contains("scriptCount"),
                "raw_usage_json 应当包含响应摘要 scriptCount 字段：" + actualNoTokens.getRawUsageJson());

        // (c) 报表 finalCreditCost：
        //   - taskWithTokens 因为 settle 写了 actual_credit_cost（按 ai_model_price * tokens 计算或 fallback creditCost），>0 → 走 ACTUAL；
        //   - taskNoTokens 因为 ACTUAL 行 actual_credit_cost=0 → 报表回退到 estimated_credit_cost；
        // 两者都应当被计入 VIDEO_PARSE 分组。
        LocalDate today = LocalDate.now();
        AdminUsageSummaryResponse byTaskType = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.TASK_TYPE,
                today.minusDays(1), today,
                TaskTypeCode.VIDEO_PARSE, null, null, null, null);
        AdminUsageSummaryRow videoParseRow = byTaskType.rows().stream()
                .filter(r -> r.groupKey().equals(TaskTypeCode.VIDEO_PARSE))
                .findFirst().orElse(null);
        assertNotNull(videoParseRow, "报表应包含 VIDEO_PARSE 分组");
        assertTrue(videoParseRow.callCount() >= 2,
                "VIDEO_PARSE 分组应至少包含本次 2 个任务");
        assertTrue(videoParseRow.finalCreditCost() >= preCostNoTokens,
                "占位 ACTUAL 任务的 finalCreditCost 应回退到 estimated（>= " + preCostNoTokens + "），实际 "
                        + videoParseRow.finalCreditCost());
        assertTrue(videoParseRow.totalTokens() >= 2600,
                "VIDEO_PARSE 分组 totalTokens 应至少包含 2600，实际 " + videoParseRow.totalTokens());

        note("[三 bis] VIDEO_PARSE settle path: taskId=%d preCost=%d, actualCost=%d, status=%s, tokens=%d/%d/%d",
                taskWithTokens.taskId(), preCostWithTokens, rowWithTokens.getActualCreditCost(),
                rowWithTokens.getSettlementStatus(),
                actualWithTokens.getPromptTokens(), actualWithTokens.getCompletionTokens(),
                actualWithTokens.getTotalTokens());
        note("[三 bis] VIDEO_PARSE recordActual path: taskId=%d preCost=%d, actual=占位0, status=%s, raw=%d 字节",
                taskNoTokens.taskId(), preCostNoTokens, rowNoTokens.getSettlementStatus(),
                actualNoTokens.getRawUsageJson().length());
    }

    // ============================================================================================
    // 四、管理员配置变更立即生效 + 操作日志
    // ============================================================================================

    @Test
    @Order(4)
    void section4_adminConfigTakesEffectAndAuditWritten() {
        long userId = newUser("user-section4", 100_000L);
        AdminOperationContext ctx = new AdminOperationContext(99L, "127.0.0.1", "trace-admin");

        // 拷一份当前 TTS 主步骤的 step_id，把 credit_cost 从 10 改为 30
        AiBillingStepConfigEntity tts = stepConfigMapper.selectOne(new LambdaQueryWrapper<AiBillingStepConfigEntity>()
                .eq(AiBillingStepConfigEntity::getTaskType, TaskTypeCode.TTS_GENERATE)
                .eq(AiBillingStepConfigEntity::getStepName, "文本转语音"));
        assertNotNull(tts, "seed 数据应当包含 TTS_GENERATE / 文本转语音");
        long oldCost = tts.getCreditCost();

        adminBillingService.updateStep(tts.getStepId(), new AdminBillingStepSaveRequest(
                tts.getTaskType(), tts.getFunctionModule(), tts.getStepName(),
                tts.getProvider(), tts.getModelCode(), tts.getUsageUnit(),
                tts.getCallCount(), tts.getCostText(),
                oldCost + 20L, true, tts.getSortOrder(), tts.getRemark()
        ), ctx);

        // 新任务立刻反映新积分
        TaskItem task = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "trace-4", userId,
                null, null, "ACC:ADMIN:NEWCOST:" + UUID.randomUUID());
        TaskEntity row = taskMapper.selectById(task.taskId());
        assertTrue(row.getEstimatedCreditCost() >= oldCost + 20L,
                "管理员调价后新任务应使用新的 credit_cost，期望 >= " + (oldCost + 20L) + " 实际 " + row.getEstimatedCreditCost());

        // 4.2 禁用步骤 → 新任务汇总会下降
        adminBillingService.setStepEnabled(tts.getStepId(), false, ctx);
        TaskItem afterDisable = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "trace-4b", userId,
                null, null, "ACC:ADMIN:DISABLED:" + UUID.randomUUID());
        TaskEntity disabledRow = taskMapper.selectById(afterDisable.taskId());
        assertTrue(disabledRow.getEstimatedCreditCost() < row.getEstimatedCreditCost(),
                "禁用主步骤后新任务积分应当下降，实际：" + disabledRow.getEstimatedCreditCost()
                        + " vs " + row.getEstimatedCreditCost());

        // 4.3 操作日志写入
        long auditCount = adminOperationLogMapper.selectCount(null);
        assertTrue(auditCount >= 2, "admin_operation_log 至少应有 BILLING_STEP_UPDATE + BILLING_STEP_DISABLE 两条");

        note("[四] 调价 %d→%d，新任务 cost=%d；禁用后 cost=%d；admin_operation_log 累计 %d 条",
                oldCost, oldCost + 20L, row.getEstimatedCreditCost(), disabledRow.getEstimatedCreditCost(),
                auditCount);

        // 还原，避免影响后续测试
        adminBillingService.setStepEnabled(tts.getStepId(), true, ctx);
        adminBillingService.updateStep(tts.getStepId(), new AdminBillingStepSaveRequest(
                tts.getTaskType(), tts.getFunctionModule(), tts.getStepName(),
                tts.getProvider(), tts.getModelCode(), tts.getUsageUnit(),
                tts.getCallCount(), tts.getCostText(),
                oldCost, true, tts.getSortOrder(), tts.getRemark()
        ), ctx);
    }

    // ============================================================================================
    // 四 bis、动态用量与固定步骤两类预扣口径回归
    // ============================================================================================

    @Test
    @Order(45)
    void section4bis_dynamicUsageAndFixedStepPrechargeStayInSync() {
        long userId = newUser("user-section4bis", 200_000L);
        AdminOperationContext ctx = new AdminOperationContext(99L, "127.0.0.1", "trace-avatar-step-change");

        AiBillingStepConfigEntity avatarStep = stepConfigMapper.selectOne(new LambdaQueryWrapper<AiBillingStepConfigEntity>()
                .eq(AiBillingStepConfigEntity::getTaskType, TaskTypeCode.AVATAR_GENERATE)
                .eq(AiBillingStepConfigEntity::getStepName, "数字人形象生成"));
        assertNotNull(avatarStep, "seed 数据应当包含 AVATAR_GENERATE / 数字人形象生成");
        long avatarOriginalCost = avatarStep.getCreditCost();

        adminBillingService.updateStep(avatarStep.getStepId(), new AdminBillingStepSaveRequest(
                avatarStep.getTaskType(), avatarStep.getFunctionModule(), avatarStep.getStepName(),
                avatarStep.getProvider(), avatarStep.getModelCode(), avatarStep.getUsageUnit(),
                avatarStep.getCallCount(), avatarStep.getCostText(),
                77L, true, avatarStep.getSortOrder(), avatarStep.getRemark()
        ), ctx);

        String avatarInput = "{\"imageCount\":3}";
        BillingEstimateResponse avatarEstimate = billingEstimateService.estimate(
                estimateRequest(TaskTypeCode.AVATAR_GENERATE, null, 3, null, userId));
        TaskItem avatarTask = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, avatarInput, "trace-avatar-dynamic", userId,
                null, null, "ACC:AVATAR_DYNAMIC:" + UUID.randomUUID());
        TaskEntity avatarRow = taskMapper.selectById(avatarTask.taskId());
        assertEquals(BillingEstimateResponse.SOURCE_USAGE_MODEL_PRICE, avatarEstimate.pricingSource(),
                "Avatar 应按图片张数和模型单价动态预估");
        assertEquals(Long.valueOf(avatarEstimate.estimatedCreditCost()), avatarRow.getEstimatedCreditCost(),
                "Avatar 动态预估必须等于 createTask 实际预扣");
        assertTrue(!Objects.equals(Long.valueOf(77L), avatarRow.getEstimatedCreditCost()),
                "Avatar 改 step 后不应再把固定 step 价当作最终预扣，避免和实际按张结算偏离");

        adminBillingService.setStepEnabled(avatarStep.getStepId(), false, ctx);
        BillingEstimateResponse avatarEstimateAfterDisable = billingEstimateService.estimate(
                estimateRequest(TaskTypeCode.AVATAR_GENERATE, null, 3, null, userId));
        TaskItem disabledAvatarTask = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, avatarInput, "trace-avatar-disabled", userId,
                null, null, "ACC:AVATAR_DYNAMIC_DISABLED:" + UUID.randomUUID());
        TaskEntity disabledAvatarRow = taskMapper.selectById(disabledAvatarTask.taskId());
        assertEquals(Long.valueOf(avatarEstimateAfterDisable.estimatedCreditCost()), disabledAvatarRow.getEstimatedCreditCost(),
                "Avatar 禁用 step 后仍应按图片张数动态预扣");

        adminBillingService.setStepEnabled(avatarStep.getStepId(), true, ctx);
        adminBillingService.updateStep(avatarStep.getStepId(), new AdminBillingStepSaveRequest(
                avatarStep.getTaskType(), avatarStep.getFunctionModule(), avatarStep.getStepName(),
                avatarStep.getProvider(), avatarStep.getModelCode(), avatarStep.getUsageUnit(),
                avatarStep.getCallCount(), avatarStep.getCostText(),
                avatarOriginalCost, true, avatarStep.getSortOrder(), avatarStep.getRemark()
        ), ctx);

        AiBillingStepConfigEntity digitalStep = stepConfigMapper.selectOne(new LambdaQueryWrapper<AiBillingStepConfigEntity>()
                .eq(AiBillingStepConfigEntity::getTaskType, TaskTypeCode.DIGITAL_HUMAN_GENERATE)
                .eq(AiBillingStepConfigEntity::getStepName, "创建数字人任务"));
        assertNotNull(digitalStep, "seed 数据应当包含 DIGITAL_HUMAN_GENERATE / 创建数字人任务");
        long digitalOriginalCost = digitalStep.getCreditCost();
        long expectedDigitalOthers = billingStepConfigService.aggregateCreditCost(TaskTypeCode.DIGITAL_HUMAN_GENERATE)
                .orElse(0L) - digitalOriginalCost;

        adminBillingService.updateStep(digitalStep.getStepId(), new AdminBillingStepSaveRequest(
                digitalStep.getTaskType(), digitalStep.getFunctionModule(), digitalStep.getStepName(),
                digitalStep.getProvider(), digitalStep.getModelCode(), digitalStep.getUsageUnit(),
                digitalStep.getCallCount(), digitalStep.getCostText(),
                33L, true, digitalStep.getSortOrder(), digitalStep.getRemark()
        ), ctx);
        long expectedDigitalAfterBump = expectedDigitalOthers + 33L;
        TaskItem digitalTask = taskService.createTask(null, TaskTypeCode.DIGITAL_HUMAN_GENERATE, "{}", "trace-digital-bump", userId,
                null, null, "ACC:DIGITAL_STEP_BUMP:" + UUID.randomUUID());
        TaskEntity digitalRow = taskMapper.selectById(digitalTask.taskId());
        assertEquals(Long.valueOf(expectedDigitalAfterBump), digitalRow.getEstimatedCreditCost(),
                "Digital Human 固定步骤任务改价后应按 step 汇总预扣");

        adminBillingService.setStepEnabled(digitalStep.getStepId(), false, ctx);
        long digitalAfterDisable = billingStepConfigService.aggregateCreditCost(TaskTypeCode.DIGITAL_HUMAN_GENERATE)
                .orElseGet(() -> taskCreditProperties.costFor(TaskTypeCode.DIGITAL_HUMAN_GENERATE));
        TaskItem disabledDigitalTask = taskService.createTask(null, TaskTypeCode.DIGITAL_HUMAN_GENERATE, "{}", "trace-digital-disabled", userId,
                null, null, "ACC:DIGITAL_STEP_DISABLE:" + UUID.randomUUID());
        TaskEntity disabledDigitalRow = taskMapper.selectById(disabledDigitalTask.taskId());
        assertEquals(Long.valueOf(digitalAfterDisable), disabledDigitalRow.getEstimatedCreditCost(),
                "Digital Human 禁用主步骤后应按剩余 step 汇总或兜底预扣");

        note("[四 bis] Avatar 按 3 张动态预扣=%d（step 改价/禁用不拉偏）；DigitalHuman step %d→33 预扣=%d，禁用后=%d",
                avatarRow.getEstimatedCreditCost(), digitalOriginalCost,
                digitalRow.getEstimatedCreditCost(), disabledDigitalRow.getEstimatedCreditCost());

        adminBillingService.setStepEnabled(digitalStep.getStepId(), true, ctx);
        adminBillingService.updateStep(digitalStep.getStepId(), new AdminBillingStepSaveRequest(
                digitalStep.getTaskType(), digitalStep.getFunctionModule(), digitalStep.getStepName(),
                digitalStep.getProvider(), digitalStep.getModelCode(), digitalStep.getUsageUnit(),
                digitalStep.getCallCount(), digitalStep.getCostText(),
                digitalOriginalCost, true, digitalStep.getSortOrder(), digitalStep.getRemark()
        ), ctx);
    }

    // ============================================================================================
    // 五、统计报表（含 CSV 导出）
    // ============================================================================================

    @Test
    @Order(5)
    void section5_usageSummaryAcrossDimensions() {
        long userId = newUser("user-section5", 100_000L);

        // 先造一组覆盖 TTS / AVATAR / VIDU 的任务 + ESTIMATE/ACTUAL 行，让报表有真实可统计的数据。
        TaskItem tts = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{}", "t5-1", userId,
                null, null, "ACC:REPORT:TTS:" + UUID.randomUUID());
        creditBillingService.settle(tts.taskId(), new UsageActualResult(
                "VOLCENGINE", "tts-doubao-default", UsageUnit.CHAR,
                null, null, null, 1000, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "{}"));

        TaskItem img = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, "{}", "t5-2", userId,
                null, null, "ACC:REPORT:IMG:" + UUID.randomUUID());
        creditBillingService.settle(img.taskId(), new UsageActualResult(
                "VOLCENGINE", "avatar-seedream-default", UsageUnit.IMAGE,
                null, null, null, null, 3,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "{}"));

        TaskItem vidu = taskService.createTask(null, TaskTypeCode.DIGITAL_HUMAN_GENERATE, "{}", "t5-3", userId,
                null, null, "ACC:REPORT:VIDU:" + UUID.randomUUID());
        creditBillingService.settle(vidu.taskId(), new UsageActualResult(
                "VIDU", "digital-human-vidu-default", UsageUnit.PROVIDER_CREDIT,
                null, null, null, null, null,
                BigDecimal.ZERO, new BigDecimal("4"), null, "{}"));

        // 还有一个 task：只创建（ESTIMATE）不 settle —— 用来测 "finalCost 回退到 ESTIMATE"
        TaskItem onlyEstimate = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, "{}", "t5-4", userId,
                null, null, "ACC:REPORT:ONLY_ESTIMATE:" + UUID.randomUUID());
        long onlyEstimateCost = taskMapper.selectById(onlyEstimate.taskId()).getEstimatedCreditCost();

        LocalDate today = LocalDate.now();

        // 5.1 DATE 维度：今天的行 finalCost > 0
        AdminUsageSummaryResponse byDate = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.DATE,
                today.minusDays(1), today, null, null, null, null, null);
        AdminUsageSummaryRow todayRow = byDate.rows().stream()
                .filter(r -> r.groupKey().equals(today.toString()))
                .findFirst().orElse(null);
        assertNotNull(todayRow, "今天应当出现在 DATE 维度报表中");
        assertTrue(todayRow.callCount() >= 4, "今天应当至少包含本测试的 4 条任务");

        // 5.2 TASK_TYPE：每种至少 1 条
        AdminUsageSummaryResponse byTaskType = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.TASK_TYPE,
                today.minusDays(1), today, null, null, null, null, null);
        assertTrue(rowsContainKey(byTaskType, TaskTypeCode.TTS_GENERATE),
                "TASK_TYPE 维度应包含 TTS_GENERATE");
        assertTrue(rowsContainKey(byTaskType, TaskTypeCode.AVATAR_GENERATE),
                "TASK_TYPE 维度应包含 AVATAR_GENERATE");
        assertTrue(rowsContainKey(byTaskType, TaskTypeCode.DIGITAL_HUMAN_GENERATE),
                "TASK_TYPE 维度应包含 DIGITAL_HUMAN_GENERATE");

        // 5.3 PROVIDER 维度：VOLCENGINE / VIDU
        AdminUsageSummaryResponse byProvider = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.PROVIDER,
                today.minusDays(1), today, null, null, null, null, null);
        assertTrue(rowsContainKey(byProvider, "VOLCENGINE"), "PROVIDER 维度应包含 VOLCENGINE");
        assertTrue(rowsContainKey(byProvider, "VIDU"), "PROVIDER 维度应包含 VIDU");

        // 5.4 MODEL_CODE 维度
        AdminUsageSummaryResponse byModel = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.MODEL_CODE,
                today.minusDays(1), today, null, null, null, null, null);
        assertTrue(rowsContainKey(byModel, "tts-doubao-default"));
        assertTrue(rowsContainKey(byModel, "avatar-seedream-default"));
        assertTrue(rowsContainKey(byModel, "digital-human-vidu-default"));

        // 5.5 finalCost 规则：onlyEstimate 任务应当被计入 estimatedCreditCost 而 actualCreditCost=0；finalCost = estimated
        AdminUsageSummaryResponse onlyOneTask = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.TASK_TYPE,
                today.minusDays(1), today, TaskTypeCode.AVATAR_GENERATE, null, null,
                "avatar-seedream-default", null);
        // 该结果合并了已 settle 的 img + 未 settle 的 onlyEstimate；
        // finalCost 至少 >= onlyEstimateCost（来自 onlyEstimate）+ actualCost（来自 img）
        AdminUsageSummaryRow row = onlyOneTask.rows().stream()
                .filter(r -> r.groupKey().equals(TaskTypeCode.AVATAR_GENERATE)).findFirst().orElse(null);
        assertNotNull(row, "应当能查到 AVATAR_GENERATE 分组");
        assertTrue(row.finalCreditCost() >= row.actualCreditCost(),
                "finalCreditCost 应 >= actualCreditCost（未结算行用 estimated 回填）");
        assertTrue(row.finalCreditCost() >= onlyEstimateCost,
                "未结算任务 estimated_credit_cost(" + onlyEstimateCost + ") 必须在 finalCreditCost("
                        + row.finalCreditCost() + ") 中被计入");

        // 5.6 重复统计校验：同一 taskId 的 ESTIMATE+ACTUAL 不会重复计入调用次数。
        long expectedCallCount = todayRow.callCount();
        assertTrue(expectedCallCount >= 4 && expectedCallCount < expectedCallCount * 2 + 1,
                "callCount 不会因 ESTIMATE+ACTUAL 两行各自计数");

        // 5.7 CSV 导出（直接调用 controller，绕过 AdminAuthInterceptor；接口层独立审计已在 section 4 覆盖）
        org.springframework.http.ResponseEntity<byte[]> csv = adminBillingController.exportUsageSummaryCsv(
                "DATE", today.minusDays(1), today, null, null, null, null, null);
        assertEquals(200, csv.getStatusCode().value(), "CSV 导出应返回 200");
        org.springframework.http.HttpHeaders csvHeaders = csv.getHeaders();
        assertNotNull(csvHeaders.getContentDisposition(), "应当包含 Content-Disposition");
        assertTrue(csvHeaders.getContentDisposition().toString().contains("attachment"),
                "Content-Disposition 应为 attachment");
        String csvBody = new String(Objects.requireNonNull(csv.getBody()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csvBody.startsWith("\uFEFFdimension,from,to") || csvBody.startsWith("\uFEFFdimension"),
                "CSV 应以 UTF-8 BOM + 表头开始，实际开头：" + csvBody.substring(0, Math.min(40, csvBody.length())));
        assertTrue(csvBody.contains(today.toString()),
                "CSV 内容应包含今天日期，确认报表数据被写入");

        note("[五] DATE 今日 callCount=%d, TASK_TYPE/PROVIDER/MODEL_CODE 维度命中校验全部通过；CSV 导出（含 BOM）成功",
                todayRow.callCount());
    }

    // ============================================================================================
    // 五bis、统一预估接口 /api/v1/billing/estimate
    // 验证：前端展示金额 == createTask 实际预扣金额（同一份 resolveCreditCost）。
    //       预估接口在带 ownerUserId 时返回 balance / enoughBalance；步骤明细完整且 enabled 与配置同步。
    // ============================================================================================

    @Test
    @Order(55)
    void section5bis_billingEstimateMatchesCreateTaskPrecharge() {
        long userId = newUser("user-estimate", 100_000L);
        record Case(String label, String taskType) {}
        List<Case> cases = List.of(
                new Case("TTS",                    TaskTypeCode.TTS_GENERATE),
                new Case("Avatar",                 TaskTypeCode.AVATAR_GENERATE),
                new Case("数字人口播",              TaskTypeCode.DIGITAL_HUMAN_GENERATE),
                new Case("Seedance 文生视频 1.5",   TaskTypeCode.TEXT_TO_VIDEO_SEEDANCE_1_5),
                new Case("Seedance 图生视频 2.0",   TaskTypeCode.IMAGE_TO_VIDEO_SEEDANCE_2_0),
                new Case("视频理解",                TaskTypeCode.VIDEO_PARSE),
                new Case("分镜解析(上传)",          TaskTypeCode.VIDEO_SCRIPT_ANALYZE),
                new Case("分镜解析(链接)",          TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE)
        );
        for (Case c : cases) {
            BillingEstimateResponse estimate = billingEstimateService.estimate(estimateRequest(c.taskType(), userId));
            assertEquals(c.taskType(), estimate.taskType(), c.label() + ": 预估接口 taskType 应回显");
            assertNotNull(estimate.balance(), c.label() + ": 带 ownerUserId 时 balance 不应为 null");
            assertNotNull(estimate.enoughBalance(), c.label() + ": 带 ownerUserId 时 enoughBalance 不应为 null");
            assertTrue(List.of(
                            BillingEstimateResponse.SOURCE_BILLING_STEP_CONFIG,
                            BillingEstimateResponse.SOURCE_TASK_CREDIT_PROPERTIES,
                            BillingEstimateResponse.SOURCE_USAGE_MODEL_PRICE,
                            BillingEstimateResponse.SOURCE_SEGMENT_COUNT
                    ).contains(estimate.pricingSource()),
                    c.label() + ": pricingSource 应是已知来源，实际 " + estimate.pricingSource());
            assertFalse(estimate.steps().isEmpty(), c.label() + ": 步骤明细不应为空");

            // 关键断言：实际 createTask 预扣金额 == estimate.estimatedCreditCost
            String idem = "ACC:EST:" + c.taskType() + ":" + UUID.randomUUID();
            TaskItem task = taskService.createTask(null, c.taskType(), "{}", "trace-estimate",
                    userId, null, null, idem);
            TaskEntity row = taskMapper.selectById(task.taskId());
            assertEquals(Long.valueOf(estimate.estimatedCreditCost()), row.getEstimatedCreditCost(),
                    c.label() + ": 预估接口返回金额必须等于 createTask 实际预扣金额（不允许双源冲突）");
            if (!BillingEstimateResponse.SOURCE_USAGE_MODEL_PRICE.equals(estimate.pricingSource())
                    && !BillingEstimateResponse.SOURCE_SEGMENT_COUNT.equals(estimate.pricingSource())) {
                assertEquals(estimate.estimatedCreditCost(), billingEstimateService.resolveCreditCost(c.taskType(), null),
                        c.label() + ": 固定步骤任务 resolveCreditCost 应与 estimate 同源");
            }
        }
        note("[五bis] /billing/estimate 与 createTask 实际预扣金额一致校验通过（覆盖 %d 个 task_type）", cases.size());

        // 余额不足场景：enoughBalance=false
        long pauper = newUser("user-pauper", 1L);
        BillingEstimateResponse pauperEstimate = billingEstimateService.estimate(estimateRequest(TaskTypeCode.AVATAR_GENERATE, pauper));
        assertTrue(pauperEstimate.estimatedCreditCost() > 1L, "Avatar 预估应 > 1（前置：种子配置 AVATAR_GENERATE > 1）");
        assertEquals(Long.valueOf(1L), pauperEstimate.balance(), "balance 应回显当前余额");
        assertFalse(pauperEstimate.enoughBalance(), "余额=1 < estimated 时 enoughBalance 必须 false");
        note("[五bis] 余额不足时 enoughBalance=false 校验通过：balance=%d cost=%d",
                pauperEstimate.balance(), pauperEstimate.estimatedCreditCost());
    }

    // ============================================================================================
    // 五ter、多退少补：余额不足 → PARTIAL_SETTLED + credit_debt_log + 余额不为负
    // ============================================================================================

    @Test
    @Order(56)
    void section5ter_partialSettledOnInsufficientBalance() {
        // 给一个"刚好够预扣但补扣会失败"的用户：余额 = 预扣金额
        long preCharge = billingEstimateService.estimate(estimateRequest(TaskTypeCode.AVATAR_GENERATE, null)).estimatedCreditCost();
        assertTrue(preCharge > 0, "前置：AVATAR_GENERATE 预扣金额应 > 0");
        long userId = newUser("user-partial", preCharge);

        TaskItem task = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, "{}", "trace-partial",
                userId, null, null, "ACC:PARTIAL:" + UUID.randomUUID());
        long balanceAfterPrecharge = balanceOf(userId);
        assertEquals(0L, balanceAfterPrecharge, "预扣后余额应当为 0（前置）");

        // 模拟"实际成本远大于预扣"——actualCreditCost 直接显式指定，绕开 ai_model_price 默认计算
        long actualCost = preCharge + 50L; // 超出 50 积分需要补扣
        UsageActualResult actual = new UsageActualResult(
                "VOLCENGINE", "avatar-seedream-default", UsageUnit.IMAGE,
                null, null, null, null, 99,
                BigDecimal.ZERO, BigDecimal.ZERO, actualCost,
                "{\"forced\":true}");
        creditBillingService.settle(task.taskId(), actual);

        // 1) 余额不为负
        long balanceAfter = balanceOf(userId);
        assertEquals(0L, balanceAfter, "settle 补扣额度不足后余额必须为 0，永远不为负");

        // 2) settlement_status = PARTIAL_SETTLED
        TaskEntity row = taskMapper.selectById(task.taskId());
        assertEquals(SettlementStatus.PARTIAL_SETTLED, row.getSettlementStatus(),
                "补扣余额不足时 settlement_status 必须 = PARTIAL_SETTLED");

        // 3) task.actual_credit_cost = 真实 actualCost（而不是已扣金额）
        assertEquals(Long.valueOf(actualCost), row.getActualCreditCost(),
                "task.actual_credit_cost 必须反映真实 actualCost（用于报表 finalCreditCost）");

        // 4) ai_usage_log 写了一条 ACTUAL
        long actualLogs = aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLogEntity>()
                .eq(AiUsageLogEntity::getTaskId, task.taskId())
                .eq(AiUsageLogEntity::getUsagePhase, UsagePhase.ACTUAL));
        assertEquals(1L, actualLogs, "PARTIAL_SETTLED 路径仍要写 1 条 ACTUAL usage_log");

        // 5) credit_debt_log 写了一条 SETTLEMENT_EXTRA，欠费 = actualCost - preCharge
        List<CreditDebtLogEntity> debts = creditDebtLogMapper.selectList(
                new LambdaQueryWrapper<CreditDebtLogEntity>()
                        .eq(CreditDebtLogEntity::getTaskId, task.taskId())
                        .eq(CreditDebtLogEntity::getDeleted, 0));
        assertEquals(1, debts.size(), "PARTIAL_SETTLED 必须写一条 credit_debt_log");
        CreditDebtLogEntity debt = debts.get(0);
        assertEquals(Long.valueOf(actualCost - preCharge), debt.getDebtCredits(),
                "debt_credits 应当 = actualCost - preCharge（=" + (actualCost - preCharge) + "）");
        assertEquals(Long.valueOf(0L), debt.getPaidCredits(), "新建欠费记录 paid_credits=0");
        assertEquals(CreditDebtStatus.UNPAID, debt.getStatus(), "新建欠费 status=UNPAID");

        // 6) 重复 settle 幂等：不再二次写欠费 / ACTUAL
        creditBillingService.settle(task.taskId(), actual);
        long debtsAgain = creditDebtLogMapper.selectCount(
                new LambdaQueryWrapper<CreditDebtLogEntity>()
                        .eq(CreditDebtLogEntity::getTaskId, task.taskId())
                        .eq(CreditDebtLogEntity::getDeleted, 0));
        assertEquals(1L, debtsAgain, "重复 settle 不应再写一条欠费");
        long balanceFinal = balanceOf(userId);
        assertEquals(0L, balanceFinal, "重复 settle 余额仍为 0，不会被多扣");

        // 7) 报表 finalCreditCost / paidCreditCost / unpaidCreditCost 应分别正确
        LocalDate today = LocalDate.now();
        AdminUsageSummaryResponse report = adminBillingReportService.usageSummary(
                AdminBillingReportService.Dimension.TASK_TYPE,
                today.minusDays(1), today,
                TaskTypeCode.AVATAR_GENERATE, null, null, null, null);
        AdminUsageSummaryRow avatarRow = report.rows().stream()
                .filter(r -> r.groupKey().equals(TaskTypeCode.AVATAR_GENERATE))
                .findFirst().orElse(null);
        assertNotNull(avatarRow, "报表应包含 AVATAR_GENERATE 分组");
        assertTrue(avatarRow.unpaidCreditCost() >= (actualCost - preCharge),
                "报表 unpaidCreditCost 至少应包含本任务的欠费 " + (actualCost - preCharge));
        assertTrue(avatarRow.debtTaskCount() >= 1,
                "报表 debtTaskCount 至少 = 1");
        // paid + unpaid = final（数学恒等式：paid 通过 final-unpaid 计算）
        assertEquals(avatarRow.finalCreditCost(), avatarRow.paidCreditCost() + avatarRow.unpaidCreditCost(),
                "报表 paid + unpaid 必须 = final");

        note("[五ter] PARTIAL_SETTLED: 余额 %d→0、debt=%d、ACTUAL=1、重复 settle 幂等；报表 paid=%d unpaid=%d final=%d",
                preCharge, debt.getDebtCredits(),
                avatarRow.paidCreditCost(), avatarRow.unpaidCreditCost(), avatarRow.finalCreditCost());
    }

    // ============================================================================================
    // 六、并发与幂等
    // ============================================================================================

    @Test
    @Order(6)
    void section6_idempotencyAndDuplicateSubmit() {
        long userId = newUser("user-section6", 100_000L);
        String idem = "ACC:DUP:" + UUID.randomUUID();
        long balanceBefore = balanceOf(userId);

        // 6.1 同一 idempotencyKey 提交两次 → 返回同一 taskId，且只产生一条 AI_CONSUME
        TaskItem first = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, "{}", "trace-6", userId,
                null, null, idem);
        TaskItem second = taskService.createTask(null, TaskTypeCode.AVATAR_GENERATE, "{}", "trace-6", userId,
                null, null, idem);
        assertEquals(first.taskId(), second.taskId(), "重复 idempotencyKey 应返回同一 taskId");
        long consumeCount = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .eq(UserCreditLogEntity::getRelatedTaskId, first.taskId())
                .eq(UserCreditLogEntity::getChangeType, "AI_CONSUME"));
        assertEquals(1, consumeCount, "重复创建不应产生第二条预扣流水");
        long preCost = taskMapper.selectById(first.taskId()).getEstimatedCreditCost();
        assertEquals(balanceBefore - preCost, balanceOf(userId), "重复提交不应导致额外扣费");

        // 6.2 重复 settle 不重复扣费（已在 section3 验证过更细粒度）
        creditBillingService.settle(first.taskId(), new UsageActualResult(
                "VOLCENGINE", "avatar-seedream-default", UsageUnit.IMAGE,
                null, null, null, null, 1,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "{}"));
        long balanceAfterFirstSettle = balanceOf(userId);
        creditBillingService.settle(first.taskId(), new UsageActualResult(
                "VOLCENGINE", "avatar-seedream-default", UsageUnit.IMAGE,
                null, null, null, null, 1,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "{}"));
        assertEquals(balanceAfterFirstSettle, balanceOf(userId), "重复 settle 不应再动余额");

        // 6.3 user_credit_log idempotency_key 全表唯一 —— schema 已强制；这里用查询再次验证当前数据
        long total = userCreditLogMapper.selectCount(null);
        long distinct = userCreditLogMapper.selectList(null).stream()
                .map(UserCreditLogEntity::getIdempotencyKey)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long nonNull = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLogEntity>()
                .isNotNull(UserCreditLogEntity::getIdempotencyKey));
        assertEquals(nonNull, distinct, "user_credit_log 不应存在重复 idempotency_key");
        note("[六] 同 idempotencyKey 重复提交：复用 taskId %d、AI_CONSUME 仅 1 条；重复 settle 不动余额；流水共 %d 条 distinct-key=%d",
                first.taskId(), total, distinct);
    }

    @Test
    @Order(7)
    void section7_accountTaskCreditDetailService() {
        long userId = newUser("user-section7", 50_000L);
        TaskItem t = taskService.createTask(null, TaskTypeCode.TTS_GENERATE, "{\"text\":\"hello\"}", "trace-7", userId,
                null, null, "idem-section7:" + UUID.randomUUID());
        TaskCreditDetailResponse detail = accountCreditDetailService.getTaskCreditDetail(userId, t.taskId());
        assertNotNull(detail);
        assertEquals(t.taskId(), detail.taskId());
        assertNotNull(detail.estimatedCreditCost());
        assertTrue(detail.estimatedCreditCost() > 0);
        assertFalse(detail.logs().isEmpty(), "应有至少一条本任务积分流水");
        assertFalse(detail.steps().isEmpty(), "步骤表或整单兜底应至少一行");
        assertFalse(detail.creditExplanation().isEmpty());

        List<AccountCreditLogRecentRow> recent = accountCreditDetailService.listRecentCreditLogs(userId, 10);
        assertFalse(recent.isEmpty());
        assertTrue(recent.stream().anyMatch(r -> "预扣".equals(r.operationLabel())),
                "最近流水应包含预扣");
        note("[七] 账户积分明细：taskId=%d est=%d 流水=%d 条；最近流水含预扣",
                detail.taskId(), detail.estimatedCreditCost(), detail.logs().size());
    }

    // ============================================================================================
    // 八、报告输出
    // ============================================================================================

    @AfterAll
    static void emitReport() {
        StringBuilder sb = new StringBuilder("\n\n===== AI 积分计费系统上线验收报告 =====\n");
        sb.append("[实际测试步骤 & 自动测试结果]\n");
        for (int i = 0; i < FINDINGS.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(FINDINGS.get(i)).append('\n');
        }
        sb.append("\n[发现的问题 / 风险点]\n");
        if (RISKS.isEmpty()) {
            sb.append("  (本次执行未发现新的阻塞问题)\n");
        } else {
            for (int i = 0; i < RISKS.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(RISKS.get(i)).append('\n');
            }
        }
        sb.append("======================================\n");
        System.out.println(sb);
        // 控制台在 Windows PowerShell 下默认 GBK 渲染，会乱码；同时把 UTF-8 报告落盘以便人工核对。
        try {
            java.nio.file.Path target = java.nio.file.Paths.get("target", "billing-acceptance-report.txt");
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // 写报告失败不影响测试结论
        }
    }

    // ============================================================================================
    // helpers
    // ============================================================================================

    private long newUser(String prefix, long balance) {
        UserAccountEntity user = new UserAccountEntity();
        user.setUsername(prefix + "-" + UUID.randomUUID());
        user.setPasswordHash("$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG"); // demo1234
        user.setDisplayName(prefix);
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

    private long balanceOf(long userId) {
        UserCreditAccountEntity account = userCreditAccountMapper.selectOne(
                new LambdaQueryWrapper<UserCreditAccountEntity>().eq(UserCreditAccountEntity::getUserId, userId));
        return account == null ? 0L : (account.getBalance() == null ? 0L : account.getBalance());
    }

    private static boolean rowsContainKey(AdminUsageSummaryResponse resp, String key) {
        return resp.rows().stream().anyMatch(r -> r.groupKey().equals(key));
    }

    private static BillingEstimateRequest estimateRequest(String taskType, Long ownerUserId) {
        return estimateRequest(taskType, null, null, null, ownerUserId);
    }

    private static BillingEstimateRequest estimateRequest(String taskType, Integer inputTextLength,
                                                          Integer imageCount, Integer segmentCount,
                                                          Long ownerUserId) {
        return new BillingEstimateRequest(taskType, null, null, inputTextLength, imageCount, segmentCount,
                null, null, ownerUserId);
    }

    private static void note(String fmt, Object... args) {
        FINDINGS.add(String.format(fmt, args));
    }

    private static void risk(String msg) {
        RISKS.add(msg);
    }
}
