package com.huashuo.admin.vo;

public record AdminDashboardSummaryResponse(
        long userCount,
        long todayNewUserCount,
        long todayTaskCount,
        long todayCreditConsumed,
        long failedTaskCount,
        long queueBacklog
) {
}
