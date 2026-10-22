package com.doiwsc.order.domain;

/**
 * Stable machine-readable failure codes surfaced on the order JSON. Kept in sync
 * with docs/CONTRACT.md §3.2; the orchestrator drives these via FailOrder(reason).
 */
public final class FailureReason {
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";
    public static final String INVENTORY_UNAVAILABLE = "INVENTORY_UNAVAILABLE";
    public static final String PAYMENT_UNAVAILABLE = "PAYMENT_UNAVAILABLE";
    public static final String ORDER_UPDATE_FAILED = "ORDER_UPDATE_FAILED";
    public static final String ORCHESTRATOR_ERROR = "ORCHESTRATOR_ERROR";

    private FailureReason() {
    }
}
