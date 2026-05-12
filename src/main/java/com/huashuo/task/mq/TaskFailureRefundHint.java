package com.huashuo.task.mq;

/**
 * 执行器在抛出异常前，可通过 {@link #set(boolean)} 告诉消费者：本次失败是否应当退款。
 * 默认 true（外部 API 尚未受理，退款安全）。executor 在调用外部 API 成功后置 false，
 * 表示资源已被第三方消耗，retryable 失败不应退款。消费者在 ack/nack 后必须 {@link #clear()}。
 */
public final class TaskFailureRefundHint {

    private static final ThreadLocal<Boolean> REFUND = new ThreadLocal<>();

    private TaskFailureRefundHint() {
    }

    public static void set(boolean refund) {
        REFUND.set(refund);
    }

    public static boolean getOrDefault(boolean fallback) {
        Boolean current = REFUND.get();
        return current == null ? fallback : current;
    }

    public static void clear() {
        REFUND.remove();
    }
}
