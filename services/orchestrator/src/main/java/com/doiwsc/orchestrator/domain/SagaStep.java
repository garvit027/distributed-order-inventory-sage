package com.doiwsc.orchestrator.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single recorded saga step. Every step the engine runs (and every
 * compensation) produces one row so GetSaga can return the full history.
 */
@Entity
@Table(name = "saga_steps")
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sagaId;

    private String name;

    @Enumerated(EnumType.STRING)
    private StepStatus status = StepStatus.RUNNING;

    private String detail;

    private Long startedAtUnixMs;

    private Long finishedAtUnixMs;

    protected SagaStep() {
    }

    public SagaStep(String sagaId, String name) {
        this.sagaId = sagaId;
        this.name = name;
        this.status = StepStatus.RUNNING;
        this.startedAtUnixMs = System.currentTimeMillis();
    }

    public void finish(StepStatus status, String detail) {
        this.status = status;
        this.detail = detail;
        this.finishedAtUnixMs = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public String getSagaId() { return sagaId; }
    public String getName() { return name; }
    public StepStatus getStatus() { return status; }
    public String getDetail() { return detail; }
    public Long getStartedAtUnixMs() { return startedAtUnixMs; }
    public Long getFinishedAtUnixMs() { return finishedAtUnixMs; }
}
