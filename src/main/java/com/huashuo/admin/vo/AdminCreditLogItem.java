package com.huashuo.admin.vo;

import java.time.LocalDateTime;

public record AdminCreditLogItem(
        Long creditLogId,
        Long userId,
        String changeType,
        Long changeAmount,
        Long beforeBalance,
        Long afterBalance,
        Long relatedTaskId,
        String modelCode,
        Long operatorAdminId,
        String remark,
        LocalDateTime createdAt
) {
}
