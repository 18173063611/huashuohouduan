package com.huashuo.billing.model;

/** 欠费记录状态。 */
public final class CreditDebtStatus {

    private CreditDebtStatus() {
    }

    /** 未补扣。 */
    public static final String UNPAID = "UNPAID";
    /** 已部分补扣。 */
    public static final String PARTIAL_PAID = "PARTIAL_PAID";
    /** 全部补扣完成。 */
    public static final String PAID = "PAID";
    /** 已作废（人工冲销）。 */
    public static final String CANCELLED = "CANCELLED";
}
