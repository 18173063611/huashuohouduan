package com.huashuo.billing.model;

/** 欠费产生场景。 */
public final class CreditDebtType {

    private CreditDebtType() {
    }

    /** settle 补扣时账户余额不足产生的欠费。 */
    public static final String SETTLEMENT_EXTRA = "SETTLEMENT_EXTRA";
}
