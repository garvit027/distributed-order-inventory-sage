package com.doiwsc.orchestrator.domain;

/** Saga lifecycle states (docs/CONTRACT.md §3.1). */
public enum SagaState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    COMPENSATED
}
