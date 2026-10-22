package com.doiwsc.orchestrator.domain;

/** Per-step execution status (matches orchestrator.v1 Step.status). */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
