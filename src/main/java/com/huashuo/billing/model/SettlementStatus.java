package com.huashuo.billing.model;

public final class SettlementStatus {

    private SettlementStatus() {
    }

    public static final String NONE = "NONE";
    public static final String PRECHARGED = "PRECHARGED";
    public static final String SETTLED = "SETTLED";
    public static final String REFUNDED = "REFUNDED";
    public static final String PARTIAL_REFUNDED = "PARTIAL_REFUNDED";
    /**
     * 补扣余额不足：实际消耗大于预扣金额，账户被扣到 0，剩余差额作为欠费写入
     * {@link com.huashuo.billing.entity.CreditDebtLogEntity}，账户余额不会被扣成负数。
     */
    public static final String PARTIAL_SETTLED = "PARTIAL_SETTLED";
    public static final String SETTLE_FAILED = "SETTLE_FAILED";
}
