package com.huashuo.user.vo.account;

public record TaskCreditStepRow(
        String stepName,
        String modelApi,
        String usageUnit,
        /** 人类可读的用量/次数说明，如「1 次」「按 Token」 */
        String usageDisplay,
        Long estimatedCredits,
        Long actualCredits,
        /** 配置项 / 按比例分摊 / 汇总 等 */
        String status
) {
}
