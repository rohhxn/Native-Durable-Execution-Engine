package com.example.durable.engine;

import java.time.Instant;
import java.util.Objects;

public final class StepRecord {
    private final String workflowId;
    private final String stepKey;
    private final String stepId;
    private final long sequence;
    private final StepStatus status;
    private final String output;
    private final Instant updatedAt;

    public StepRecord(
            String workflowId,
            String stepKey,
            String stepId,
            long sequence,
            StepStatus status,
            String output,
            Instant updatedAt) {
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.stepKey = Objects.requireNonNull(stepKey, "stepKey");
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        this.sequence = sequence;
        this.status = Objects.requireNonNull(status, "status");
        this.output = output;
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getStepKey() {
        return stepKey;
    }

    public String getStepId() {
        return stepId;
    }

    public long getSequence() {
        return sequence;
    }

    public StepStatus getStatus() {
        return status;
    }

    public String getOutput() {
        return output;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public StepRecord withStatus(StepStatus newStatus, String newOutput) {
        return new StepRecord(workflowId, stepKey, stepId, sequence, newStatus, newOutput, Instant.now());
    }
}
