package com.doiwsc.orchestrator.domain;

/** Canonical step names (matches orchestrator.v1 Step.name). */
public final class StepName {
    public static final String RESERVE_INVENTORY = "RESERVE_INVENTORY";
    public static final String CHARGE_PAYMENT = "CHARGE_PAYMENT";
    public static final String CONFIRM_ORDER = "CONFIRM_ORDER";
    public static final String RELEASE_INVENTORY = "RELEASE_INVENTORY";
    public static final String REFUND_PAYMENT = "REFUND_PAYMENT";
    public static final String NOTIFY = "NOTIFY";

    private StepName() {
    }
}
