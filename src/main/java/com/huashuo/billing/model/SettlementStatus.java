package com.huashuo.billing.model;

public final class SettlementStatus {

    private SettlementStatus() {
    }

    public static final String NONE = "NONE";
    public static final String PRECHARGED = "PRECHARGED";
    public static final String SETTLED = "SETTLED";
    public static final String REFUNDED = "REFUNDED";
    public static final String PARTIAL_REFUNDED = "PARTIAL_REFUNDED";
    public static final String SETTLE_FAILED = "SETTLE_FAILED";
}
