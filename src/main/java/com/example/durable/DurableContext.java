package com.example.durable;

import com.example.durable.engine.CrashSimulator;
import com.example.durable.engine.SequenceTracker;
import com.example.durable.engine.SQLiteStepStore;
import com.example.durable.engine.StepExecutor;
import com.example.durable.engine.StepResultSerializer;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;

public final class DurableContext {
    private final StepExecutor executor;
    private final SequenceTracker tracker;

    public DurableContext(String workflowId, String jdbcUrl, long crashAfter, Duration staleTimeout) {
        SQLiteStepStore store = new SQLiteStepStore(jdbcUrl, staleTimeout);
        this.tracker = new SequenceTracker();
        StepResultSerializer serializer = new StepResultSerializer();
        CrashSimulator crashSimulator = new CrashSimulator(crashAfter);
        this.executor = new StepExecutor(workflowId, store, tracker, serializer, crashSimulator);
    }

    public <T> T step(String stepId, Class<T> type, Callable<T> fn) throws Exception {
        return executor.step(stepId, type, fn);
    }

    public <T> T step(String stepId, TypeReference<T> type, Callable<T> fn) throws Exception {
        return executor.step(stepId, type, fn);
    }

    public <T> T step(String stepId, Callable<T> fn) throws Exception {
        return executor.step(stepId, Object.class, fn);
    }

    public long reserveSequence() {
        return tracker.reserve();
    }

    public <T> T stepWithReservedSequence(long sequence, String stepId, Class<T> type, Callable<T> fn) throws Exception {
        return executor.stepWithSequence(sequence, stepId, type, fn);
    }
}
