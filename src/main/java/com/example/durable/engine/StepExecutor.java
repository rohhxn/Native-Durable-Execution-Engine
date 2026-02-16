package com.example.durable.engine;

import java.util.Optional;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class StepExecutor {
    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final String workflowId;
    private final SQLiteStepStore store;
    private final SequenceTracker sequenceTracker;
    private final StepResultSerializer serializer;
    private final CrashSimulator crashSimulator;
    private final ObjectMapper mapper = new ObjectMapper();

    public StepExecutor(String workflowId, SQLiteStepStore store, SequenceTracker sequenceTracker,
                        StepResultSerializer serializer, CrashSimulator crashSimulator) {
        this.workflowId = workflowId;
        this.store = store;
        this.sequenceTracker = sequenceTracker;
        this.serializer = serializer;
        this.crashSimulator = crashSimulator;
    }

    public <T> T step(String stepId, Class<T> type, Callable<T> fn) throws Exception {
        JavaType javaType = mapper.constructType(type);
        return execute(sequenceTracker.reserve(), stepId, javaType, fn);
    }

    public <T> T step(String stepId, TypeReference<T> type, Callable<T> fn) throws Exception {
        JavaType javaType = mapper.constructType(type);
        return execute(sequenceTracker.reserve(), stepId, javaType, fn);
    }

    public <T> T stepWithSequence(long sequence, String stepId, Class<T> type, Callable<T> fn) throws Exception {
        return execute(sequence, stepId, mapper.constructType(type), fn);
    }

    public <T> T stepWithSequence(long sequence, String stepId, TypeReference<T> type, Callable<T> fn) throws Exception {
        return execute(sequence, stepId, mapper.constructType(type), fn);
    }

    private <T> T execute(long sequence, String stepId, JavaType type, Callable<T> fn) throws Exception {
        String stepKey = workflowId + ":" + sequence;

        return store.withTransaction(conn -> {
            try {
                Optional<StepRecord> existing = store.select(workflowId, stepKey, conn);
                if (existing.isPresent()) {
                    StepRecord record = existing.get();
                    if (record.getStatus() == StepStatus.IN_PROGRESS) {
                        store.markFailedIfStale(record);
                        throw new IllegalStateException("Step currently in progress: " + stepKey);
                    }
                    if (record.getStatus() == StepStatus.COMPLETED) {
                        return serializer.deserialize(record.getOutput(), type);
                    }
                }

                StepRecord inProgress = new StepRecord(workflowId, stepKey, stepId, sequence, StepStatus.IN_PROGRESS, null, null);
                store.insertInProgress(inProgress, conn);
                crashSimulator.afterStepStarted();

                T result = fn.call();
                String output = serializer.serialize(result);
                StepRecord completed = inProgress.withStatus(StepStatus.COMPLETED, output);
                store.updateStatus(completed, conn);
                return result;
            } catch (Exception e) {
                try {
                    StepRecord failed = new StepRecord(workflowId, stepKey, stepId, sequence, StepStatus.FAILED, null, null);
                    store.updateStatus(failed, conn);
                } catch (Exception ignored) {
                    log.error("Failed to mark step as FAILED", ignored);
                }
                throw new RuntimeException(e);
            }
        });
    }
}
