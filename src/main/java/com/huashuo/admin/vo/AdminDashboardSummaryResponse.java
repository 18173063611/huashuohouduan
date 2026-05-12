package com.huashuo.admin.vo;

public record AdminDashboardSummaryResponse(
        long userCount,
        long todayTaskCount,
        long todayCreditConsumed,
        long failedTaskCount,
        long queueBacklog
) {
}
