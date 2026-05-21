package com.huashuo.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.huashuo.admin.vo.AdminDashboardSummaryResponse;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserCreditLogMapper;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final UserAccountMapper userAccountMapper;
    private final TaskMapper taskMapper;
    private final UserCreditLogMapper userCreditLogMapper;

    public AdminDashboardController(UserAccountMapper userAccountMapper, TaskMapper taskMapper,
                                    UserCreditLogMapper userCreditLogMapper) {
        this.userAccountMapper = userAccountMapper;
        this.taskMapper = taskMapper;
        this.userCreditLogMapper = userCreditLogMapper;
    }

    @GetMapping("/summary")
    public ApiResponse<AdminDashboardSummaryResponse> summary() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        LambdaQueryWrapper<UserAccountEntity> todayUserWrapper = new LambdaQueryWrapper<>();
        todayUserWrapper.ge(UserAccountEntity::getCreatedAt, todayStart)
                .lt(UserAccountEntity::getCreatedAt, tomorrowStart);

        LambdaQueryWrapper<TaskEntity> todayTaskWrapper = new LambdaQueryWrapper<>();
        todayTaskWrapper.ge(TaskEntity::getCreatedAt, todayStart)
                .lt(TaskEntity::getCreatedAt, tomorrowStart);

        LambdaQueryWrapper<TaskEntity> failedTaskWrapper = new LambdaQueryWrapper<>();
        failedTaskWrapper.in(TaskEntity::getStatus,
                TaskStatusCode.FAILED, TaskStatusCode.RETRYABLE, TaskStatusCode.CANCELED);

        LambdaQueryWrapper<TaskEntity> backlogWrapper = new LambdaQueryWrapper<>();
        backlogWrapper.eq(TaskEntity::getStatus, TaskStatusCode.QUEUED);

        return ApiResponse.success(
                new AdminDashboardSummaryResponse(
                        userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccountEntity>()),
                        userAccountMapper.selectCount(todayUserWrapper),
                        taskMapper.selectCount(todayTaskWrapper),
                        todayCreditConsumed(todayStart, tomorrowStart),
                        taskMapper.selectCount(failedTaskWrapper),
                        taskMapper.selectCount(backlogWrapper)
                ),
                traceId()
        );
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }

    private long todayCreditConsumed(LocalDateTime todayStart, LocalDateTime tomorrowStart) {
        QueryWrapper<UserCreditLogEntity> wrapper = new QueryWrapper<>();
        wrapper.select("coalesce(sum(abs(change_amount)), 0)")
                .lt("change_amount", 0)
                .ge("created_at", todayStart)
                .lt("created_at", tomorrowStart)
                .eq("deleted", 0);
        Object value = userCreditLogMapper.selectObjs(wrapper).stream().findFirst().orElse(0);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
